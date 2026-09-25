package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.nonFatalOrThrow
import arrow.core.raise.Raise
import arrow.core.right
import io.github.matthewjones372.lark.Clock
import io.github.matthewjones372.lark.Deferred
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.VirtualThreads
import io.github.matthewjones372.lark.clock
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
 * the actor, after any step already running has returned. A failure asks [restart], which restarts the actor from
 * its initial state after its delay on this flock's clock, or stops it once done; with no schedule it stops.
 */
fun <F, M : Any, S, E> Flock<F>.spawn(
    name: String,
    behaviour: Behaviour<M, S, E>,
    capacity: Int = 1024,
    throughput: Int = 64,
    restart: Schedule<Failure<E>, *>? = null,
): ActorRef<M> {
    require(capacity > 0) { "capacity must be positive, was $capacity" }
    require(throughput > 0) { "throughput must be positive, was $throughput" }
    val guardian = guardians.computeIfAbsent(this) { flock -> Guardian(flock).also { g -> async { g.stand() } } }
    val address = Address("local", "/user/$name", incarnations.incrementAndGet())
    // The flock's clock, read here: an activation runs on a thread the flock did not fork, so it would not inherit it.
    val waits = clock.get()
    return Cell(behaviour, address, capacity, throughput, on, guardian, restart, waits)
        .also { guardian.cells += it }
}

/**
 * Waits until every message told to this flock's actors, and every message those caused, has been handled or
 * dropped. The runtime counts them, so this parks until the count reaches zero rather than polling.
 */
fun <E> Flock<E>.awaitIdle() {
    guardians[this]?.backlog?.awaitEmpty()
}

/**
 * [Signal.Terminated] once [ref] has stopped, for code outside any actor. Waits on the actor's own end rather than
 * a fork, so watching holds no thread until someone awaits it.
 */
@Suppress("UnusedReceiverParameter")
fun <F> Flock<F>.watch(ref: ActorRef<*>): Deferred<Signal.Terminated> {
    val cell = requireNotNull(ref as? Cell<*, *, *>) { "$ref is not an actor on threads, so it cannot be watched" }
    return object : Deferred<Signal.Terminated> {
        override fun await(): Signal.Terminated {
            cell.ended.await()
            return Signal.Terminated(ref)
        }

        override fun cancel() = Unit
    }
}

/**
 * Waits for the latch however often the thread is interrupted, and interrupts it again afterwards. An actor's end
 * must complete once begun: an interrupt that cut it short would leave its watchers and its flock waiting.
 */
private fun CountDownLatch.awaitThroughInterrupts() {
    var interrupted = false
    while (count > 0L) {
        try {
            await()
        } catch (again: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

/** A signal on its way through a mailbox. */
private class Signalled(val signal: Signal)

/** Asks and waits on the calling thread. An actor that stops before replying answers [AskFailure.Stopped] at once. */
fun <M : Any, A : Any> ActorRef<M>.ask(within: Duration, message: (Reply<A>) -> M): Either<AskFailure, A> {
    // A test actor has handled the message before its tell returns, so there is nothing to wait for.
    if (this is TestActor<M, *, *>) return ask(message)
    val reply = Answer<A>(Address(address.node, "/temp/ask-${asks.incrementAndGet()}", 1))
    val cell = this as? Cell<M, *, *>
    if (cell != null && !cell.expect(reply)) return AskFailure.Stopped.left()
    tell(message(reply))
    val answered = reply.done.await(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)
    cell?.forget(reply)
    return if (answered) reply.outcome.get() else AskFailure.TimedOut.left()
}

private val incarnations = AtomicLong()

/** How long an actor spins for a reply before parking: past the knee of the ping-pong benchmark, 5 µs to 50 µs. */
private val LINGER_NANOS = TimeUnit.MICROSECONDS.toNanos(20)
private val asks = AtomicLong()

/** The actor whose step this thread is running, so a `tell` from inside one can refuse to wait. */
private val stepping = ThreadLocal<Cell<*, *, *>>()

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
    val cells = ConcurrentLinkedQueue<Cell<*, *, *>>()
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

private class Cell<M : Any, S, E>(
    private val behaviour: Behaviour<M, S, E>,
    override val address: Address,
    private val capacity: Int,
    private val throughput: Int,
    private val on: Executor,
    private val guardian: Guardian,
    restart: Schedule<Failure<E>, *>?,
    private val clock: Clock,
) : ActorRef<M>, Ctx<M> {
    override val self: ActorRef<M> get() = this

    // Messages, and signals wrapped in Signalled so that no message type can be taken for one.
    private val mailbox = Mailbox<Any>()
    private val room = Semaphore(capacity)

    // Held from the moment a message finds the actor idle until its activation ends, so one step runs at a time.
    private val scheduled = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val state = AtomicReference(behaviour.initial)
    private val boundary = StepRaise<E>()

    // Where the restart schedule has got to; it moves on with each failure.
    private val supervision = AtomicReference(restart?.step)
    private val pending = ConcurrentHashMap.newKeySet<Answer<*>>()
    val ended = CountDownLatch(1)
    private val watchers = ConcurrentHashMap.newKeySet<Cell<*, *, *>>()
    private val terminated = AtomicBoolean(false)

    // The thread running this actor's activation, for a stop to interrupt.
    private val running = AtomicReference<Thread?>()
    private val backlog = guardian.backlog

    // Spawned by this actor's steps, so stopped before it; only its own activation reads or writes the list.
    private val children = ConcurrentLinkedQueue<Cell<*, *, *>>()

    override fun <C : Any, T, F> spawn(
        name: String,
        behaviour: Behaviour<C, T, F>,
        restart: Schedule<Failure<F>, *>?,
    ): ActorRef<C> {
        val child = Cell(
            behaviour,
            Address(address.node, "${address.path}/$name", incarnations.incrementAndGet()),
            capacity,
            throughput,
            on,
            guardian,
            restart,
            clock,
        )
        guardian.cells += child
        children += child
        return child
    }

    /** Stops every child and waits for each to end, so none outlives or overlaps this actor's own ending. */
    private fun stopChildren() {
        generateSequence { children.poll() }.forEach { child ->
            child.stop()
            child.ended.awaitThroughInterrupts()
        }
    }

    // Whether this activation told another actor anything, so that a reply may be on its way. Read and written
    // only by the thread running this actor's activation.
    var told = false

    override fun tell(message: M) {
        if (stopped.get()) return
        val sender = stepping.get()
        sender?.told = true
        if (sender == null) {
            room.acquire()
        } else {
            check(room.tryAcquire()) { "the mailbox of ${address.path} is full" }
        }
        if (stopped.get()) return
        enqueue(message)
    }

    /** A signal takes no room: the actor that sends one may be ending, and must not wait to. */
    fun signal(signal: Signal) {
        if (!stopped.get()) enqueue(Signalled(signal))
    }

    override fun watch(ref: ActorRef<*>) {
        requireNotNull(ref as? Cell<*, *, *>) { "$ref is not an actor on threads, so it cannot be watched from one" }
            .watchedBy(this)
    }

    /** Whichever of this and [finish] takes [watcher] out of the set delivers its one `Terminated`. */
    fun watchedBy(watcher: Cell<*, *, *>) {
        if (watchers.add(watcher) && terminated.get() &&
            watchers.remove(watcher)
        ) watcher.signal(Signal.Terminated(this))
    }

    private fun enqueue(item: Any) {
        mailbox.add(item)
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

    /**
     * Stops the actor: at once when it is idle, and after its running step otherwise. On virtual threads that step is
     * interrupted, since each activation has a thread of its own that nothing else will run on; on any other executor
     * an interrupt could land on the pool's next task, so the step is waited for.
     */
    fun stop() {
        stopped.set(true)
        if (scheduled.compareAndSet(false, true)) {
            finish()
        } else if (on === VirtualThreads) {
            running.get()?.interrupt()
        }
    }

    /** One activation, counted once in the flock's backlog however many messages it handles. */
    private fun activate() {
        var returned = false
        try {
            stepping.set(this)
            running.set(Thread.currentThread())
            run()
            returned = true
        } catch (interrupted: InterruptedException) {
            // The interrupt a stop sends to a running step: the stop is the outcome, not a failure to report.
            if (!stopped.get()) throw interrupted
        } finally {
            running.set(null)
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

            lingered() -> run()

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

    /**
     * Spins for up to [LINGER_NANOS] waiting for a message, before letting the thread go, when this activation
     * told another actor something: a reply is usually that close behind, and handling it here costs nothing where
     * parking costs a carrier's wake to come back. An actor that only receives never spins, so a fan-out does not
     * pay it once per actor.
     */
    private fun lingered(): Boolean {
        if (!told) return false
        told = false
        val deadline = System.nanoTime() + LINGER_NANOS
        while (System.nanoTime() < deadline) {
            if (mailbox.isNotEmpty() || stopped.get()) return true
            Thread.onSpinWait()
        }
        return false
    }

    private tailrec fun drain(left: Int) {
        if (left == 0 || stopped.get()) return
        val item = mailbox.poll() ?: return
        val next = if (item is Signalled) {
            val handler = behaviour.signal
            if (handler == null) Next.Stay else supervised { handler(this, this@Cell, state.get(), item.signal) }
        } else {
            room.release()
            @Suppress("UNCHECKED_CAST")
            val message = item as M
            supervised { behaviour.step(this, this@Cell, state.get(), message) }
        }
        when (next) {
            Next.Stay, Next.Unhandled -> Unit
            is Next.Become -> state.set(next.state)
            Next.Stop -> stopped.set(true)
        }
        drain(left - 1)
    }

    /**
     * Restarts from the initial state after the schedule's delay, keeping the mailbox, or stops once the schedule is
     * done. With no schedule a throw goes on to the thread, as it did before there was supervision.
     */
    private fun failed(failure: Failure<E>, thrown: Throwable?): Next<S> =
        when (val decision = supervision.get()?.invoke(failure)) {
            is Schedule.Decision.Continue -> {
                stopChildren()
                clock.sleep(decision.delay)
                state.set(behaviour.initial)
                supervision.set(decision.step)
                Next.Stay
            }

            else -> {
                if (thrown != null && supervision.get() == null) throw thrown
                Next.Stop
            }
        }

    /** A step or a signal's handler, its raise and its throw handed to supervision. Inline: nothing is allocated. */
    // A throw is the actor's failure, handed to its schedule like a raise; only a fatal one is not.
    @Suppress("TooGenericExceptionCaught")
    private inline fun supervised(body: Raise<E>.() -> Next<S>): Next<S> =
        try {
            boundary.guarded(body) { error -> failed(Failure.Raised(error), null) }
        } catch (thrown: Throwable) {
            failed(Failure.Thrown(thrown.nonFatalOrThrow()), thrown)
        }

    /** Stops its children, signals [Signal.Stopping], then lets go; watchers hear `Terminated` before [ended] opens. */
    private fun finish() {
        stopChildren()
        stopping()
        mailbox.clear()
        // Wakes every sender parked on a full mailbox; each finds the actor stopped and drops its message.
        room.release(Int.MAX_VALUE / 2)
        finishAsks()
        terminated.set(true)
        watchers.forEach { if (watchers.remove(it)) it.signal(Signal.Terminated(this)) }
        ended.countDown()
    }

    // The actor is ending: a raise or a throw from its Stopping handler has no schedule left to go to.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun stopping() {
        val handler = behaviour.signal ?: return
        try {
            boundary.guarded({ handler(this, this@Cell, state.get(), Signal.Stopping) }) { Next.Stop }
        } catch (thrown: Throwable) {
            thrown.nonFatalOrThrow()
        }
    }

    private fun finishAsks() {
        pending.forEach { it.complete(AskFailure.Stopped.left()) }
    }

    override fun equals(other: Any?): Boolean = other is ActorRef<*> && other.address == address

    override fun hashCode(): Int = address.hashCode()

    override fun toString(): String = "ActorRef(${address.path}#${address.incarnation})"
}
