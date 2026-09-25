package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.Flock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * Spawns [behaviour] in this flock, on the flock's executor. The actor cannot outlive the scope: closing it stops
 * the actor, after any step already running has returned.
 */
fun <E, M : Any, S> Flock<E>.spawn(
    name: String,
    behaviour: Behaviour<M, S>,
    capacity: Int = 1024,
    throughput: Int = 64,
): ActorRef<M> {
    require(capacity > 0) { "capacity must be positive, was $capacity" }
    require(throughput > 0) { "throughput must be positive, was $throughput" }
    val guardian = guardians.computeIfAbsent(this) { flock -> Guardian(flock).also { g -> async { g.stand() } } }
    val address = Address("local", "/user/$name", incarnations.incrementAndGet())
    return Cell(behaviour, address, capacity, throughput, on, guardian.backlog).also { guardian.cells += it }
}

/**
 * Waits until every message told to this flock's actors, and every message those caused, has been handled or
 * dropped. The runtime counts them, so this parks until the count reaches zero rather than polling.
 */
fun <E> Flock<E>.awaitIdle() {
    guardians[this]?.backlog?.awaitEmpty()
}

/** Asks and waits on the calling thread. An actor that stops before replying answers [AskFailure.Stopped] at once. */
fun <M : Any, A : Any> ActorRef<M>.ask(within: Duration, message: (Reply<A>) -> M): Either<AskFailure, A> {
    // A test actor has handled the message before its tell returns, so there is nothing to wait for.
    if (this is TestActor<M, *>) return ask(message)
    val reply = Answer<A>(Address(address.node, "/temp/ask-${asks.incrementAndGet()}", 1))
    val cell = this as? Cell<M, *>
    if (cell != null && !cell.expect(reply)) return AskFailure.Stopped.left()
    tell(message(reply))
    val answered = reply.done.await(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)
    cell?.forget(reply)
    return if (answered) reply.outcome.get() else AskFailure.TimedOut.left()
}

private val incarnations = AtomicLong()
private val asks = AtomicLong()

/** The actor whose step this thread is running, so a `tell` from inside one can refuse to wait. */
private val stepping = ThreadLocal<Cell<*, *>>()

/**
 * One guardian per flock: a single parked fork that the flock interrupts on close, which then stops its actors.
 * Keyed by the flock itself, since `Flock` has no hook of its own for close.
 */
private val guardians = ConcurrentHashMap<Flock<*>, Guardian>()

/**
 * Activations running or scheduled across one flock's actors. A step's tell schedules its target before its own
 * activation ends, so the count reaches zero only once nothing is left to handle.
 */
private class Backlog {
    private val count = AtomicLong()
    private val lock = ReentrantLock()
    private val empty = lock.newCondition()

    fun added() {
        count.incrementAndGet()
    }

    fun done() {
        if (count.decrementAndGet() == 0L) lock.withLock { empty.signalAll() }
    }

    fun awaitEmpty() = lock.withLock {
        while (count.get() != 0L) empty.await()
    }
}

private class Guardian(private val flock: Flock<*>) {
    val cells = ConcurrentLinkedQueue<Cell<*, *>>()
    val backlog = Backlog()

    fun stand() {
        try {
            CountDownLatch(1).await()
        } catch (closing: InterruptedException) {
            // The flock is closing, which is the only way out of here.
        }
        guardians.remove(flock)
        cells.forEach { it.stop() }
        cells.forEach { it.ended.await() }
    }
}

private class Answer<A : Any>(override val address: Address) : Reply<A> {
    val outcome = AtomicReference<Either<AskFailure, A>>()
    val done = CountDownLatch(1)

    override fun invoke(answer: A) {
        check(complete(answer.right())) { "a second reply, $answer, after ${outcome.get()}" }
    }

    fun complete(outcome: Either<AskFailure, A>): Boolean =
        this.outcome.compareAndSet(null, outcome).also { if (it) done.countDown() }
}

private class Cell<M : Any, S>(
    private val behaviour: Behaviour<M, S>,
    override val address: Address,
    capacity: Int,
    private val throughput: Int,
    private val on: Executor,
    private val backlog: Backlog,
) : ActorRef<M>, Ctx<M> {
    override val self: ActorRef<M> get() = this

    private val mailbox = Mailbox<M>()
    private val room = Semaphore(capacity)

    // Held from the moment a message finds the actor idle until its activation ends, so one step runs at a time.
    private val scheduled = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val state = AtomicReference(behaviour.initial)
    private val pending = ConcurrentHashMap.newKeySet<Answer<*>>()
    val ended = CountDownLatch(1)

    override fun tell(message: M) {
        if (stopped.get()) return
        if (stepping.get() == null) {
            room.acquire()
        } else {
            check(room.tryAcquire()) { "the mailbox of ${address.path} is full" }
        }
        if (stopped.get()) return
        mailbox.add(message)
        // Stopped between the check and the add: nothing will handle it, and the stop has let the mailbox go.
        if (stopped.get()) return
        // Read before the compare-and-set: a busy actor's flag is almost always taken, and a failed CAS still
        // takes the cache line away from the actor's own thread.
        if (!scheduled.get() && scheduled.compareAndSet(false, true)) {
            backlog.added()
            on.execute(::activate)
        }
    }

    /** Registers an ask, so that stopping answers it. False when the actor has already stopped. */
    fun expect(reply: Answer<*>): Boolean {
        pending.add(reply)
        return !stopped.get()
    }

    fun forget(reply: Answer<*>) {
        pending.remove(reply)
    }

    fun stop() {
        stopped.set(true)
        if (scheduled.compareAndSet(false, true)) finish()
    }

    /** One activation, counted once in the flock's backlog however many messages it handles. */
    private fun activate() {
        var returned = false
        try {
            stepping.set(this)
            run()
            returned = true
        } finally {
            stepping.remove()
            // A throw stops the actor, and still reaches the thread's own handler.
            if (!returned) {
                stopped.set(true)
                finish()
                backlog.done()
            }
        }
    }

    /**
     * Handles `throughput` messages at a time. Between batches a virtual thread yields its carrier and carries on,
     * which is as fair to other actors as resubmitting and costs no new thread; any other executor is resubmitted.
     */
    private tailrec fun run() {
        drain(throughput)
        when {
            stopped.get() -> {
                finish()
                backlog.done()
            }

            mailbox.isNotEmpty() && !Thread.currentThread().isVirtual -> on.execute(::activate)

            mailbox.isNotEmpty() -> {
                Thread.yield()
                run()
            }

            else -> {
                scheduled.set(false)
                // A message that arrived after the last poll and lost the race for `scheduled` is ours to handle.
                if ((mailbox.isNotEmpty() || stopped.get()) && scheduled.compareAndSet(false, true)) {
                    run()
                } else {
                    backlog.done()
                }
            }
        }
    }

    private tailrec fun drain(left: Int) {
        if (left == 0 || stopped.get()) return
        val message = mailbox.poll() ?: return
        room.release()
        when (val next = behaviour.step(this, state.get(), message)) {
            Next.Stay, Next.Unhandled -> Unit
            is Next.Become -> state.set(next.state)
            Next.Stop -> stopped.set(true)
        }
        drain(left - 1)
    }

    private fun finish() {
        mailbox.clear()
        // Wakes every sender parked on a full mailbox; each finds the actor stopped and drops its message.
        room.release(Int.MAX_VALUE / 2)
        finishAsks()
        ended.countDown()
    }

    private fun finishAsks() {
        pending.forEach { it.complete(AskFailure.Stopped.left()) }
    }

    override fun equals(other: Any?): Boolean = other is ActorRef<*> && other.address == address

    override fun hashCode(): Int = address.hashCode()

    override fun toString(): String = "ActorRef(${address.path}#${address.incarnation})"
}
