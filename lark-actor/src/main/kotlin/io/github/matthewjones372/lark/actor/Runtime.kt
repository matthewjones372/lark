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
import kotlin.time.Duration

/**
 * Spawns [behaviour] in this flock, on the flock's executor. The actor cannot outlive the scope: closing it stops
 * the actor, after any step already running has returned.
 */
fun <E, M : Any, S> Flock<E>.spawn(
    name: String,
    behaviour: Behaviour<M, S>,
    capacity: Int = 1024,
    throughput: Int = 5,
): ActorRef<M> {
    require(capacity > 0) { "capacity must be positive, was $capacity" }
    require(throughput > 0) { "throughput must be positive, was $throughput" }
    val cell =
        Cell(behaviour, Address("local", "/user/$name", incarnations.incrementAndGet()), capacity, throughput, on)
    guardians.computeIfAbsent(this) { flock -> Guardian(flock).also { g -> async { g.stand() } } }.cells += cell
    return cell
}

/** Asks and waits on the calling thread. An actor that stops before replying answers [AskFailure.Stopped] at once. */
fun <M : Any, A : Any> ActorRef<M>.ask(within: Duration, message: (Reply<A>) -> M): Either<AskFailure, A> {
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

private class Guardian(private val flock: Flock<*>) {
    val cells = ConcurrentLinkedQueue<Cell<*, *>>()

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
) : ActorRef<M>, Ctx<M> {
    override val self: ActorRef<M> get() = this

    private val mailbox = ConcurrentLinkedQueue<M>()
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
        if (scheduled.compareAndSet(false, true)) on.execute(::activate)
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

    private fun activate() {
        var returned = false
        try {
            stepping.set(this)
            drain(throughput)
            returned = true
        } finally {
            stepping.remove()
            // A throw stops the actor, and still reaches the thread's own handler.
            if (!returned) {
                stopped.set(true)
                finish()
            }
        }
        settle()
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

    /** Ends an activation: finishes a stopped actor, yields a busy one, or goes idle without losing a late message. */
    private fun settle() {
        when {
            stopped.get() -> finish()

            mailbox.isNotEmpty() -> on.execute(::activate)

            else -> {
                scheduled.set(false)
                if ((mailbox.isNotEmpty() || stopped.get()) && scheduled.compareAndSet(false, true)) settle()
            }
        }
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
