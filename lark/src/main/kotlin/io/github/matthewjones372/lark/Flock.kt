package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Whether `async` forks where it is written, or waits until something asks for the value. */
enum class Start { Eager, Lazy }

/** A `Raise` scope that owns every thread forked in it: no fork outlives the block that opened it. */
interface Flock<E> : Raise<E> {
    /** The executor this scope's forks run on when a call does not name another. */
    val on: Executor

    /**
     * Forks [block] onto [on] under a nested scope of its own. A [Start.Lazy] fork waits for `await`, and
     * one nobody awaits never runs at all, so its raise is never the scope's `Left`.
     */
    fun <T> async(on: Executor = this.on, start: Start = Start.Eager, block: Flock<E>.() -> T): Deferred<T>
}

/**
 * Opens a scope on the calling thread and closes it when [block] leaves — by return, raise or throw —
 * interrupting every fork still running and joining all of them before this returns.
 */
fun <E, A> flock(on: Executor = VirtualThreads, block: Flock<E>.() -> A): Either<E, A> = either { flock(on, block) }

/**
 * The same scope inside a `Raise` already in hand, so `either { flock { async { } } }` needs no second
 * boundary; a fork still opens one of its own, because a `Raise` never crosses a thread.
 */
fun <E, A> Raise<E>.flock(on: Executor = VirtualThreads, block: Flock<E>.() -> A): A {
    val nest = Nest(this, on)
    val value = try {
        nest.block()
    } finally {
        nest.close()
    }
    // Only reached on a return, so a fork nobody awaited still gets to fail its parent.
    nest.unnoticedFailure()?.let { surface(it) }
    return value
}

/**
 * A fork's body opens its own boundary and its outcome is held until `await`; a throw has to be caught
 * here, or it would end the fork's thread with nobody left to answer for it.
 */
private fun <E, T> capture(on: Executor, block: Flock<E>.() -> T): Outcome<E, T> =
    try {
        either { flock(on, block) }.fold({ Raised(it) }, { Returned(it) })
    } catch (t: Throwable) {
        Thrown(t)
    }

/**
 * The owner the top-level combinators fork under: `Nothing` has no value, so nothing running inside one
 * can hand it an error to raise.
 */
internal object Unraisable : Raise<Nothing> {
    override fun raise(r: Nothing): Nothing = r
}

/**
 * The owner an accumulating combinator forks under: it keeps its branches' errors and answers with all of
 * them at once, so no fork ever hands one back to be surfaced on its own.
 */
internal object Uncollected : Raise<Any?> {
    override fun raise(r: Any?): Nothing = error("an accumulated error is answered by its combinator")
}

private fun <E> Raise<E>.surface(failure: Failure<E>): Nothing = when (failure) {
    is Raised -> raise(failure.error)
    is Thrown -> throw failure.throwable
}

/** What a fork interrupted by a combinator answers with, as against a failure the caller's work ran into. */
private fun Failure<*>.isInterrupt(): Boolean = this is Thrown && throwable is InterruptedException

/**
 * An interrupt landing on the closing thread must not leave a fork running, so the join is retried
 * and the flag handed back to the caller afterwards.
 */
private fun Thread.joinFully() {
    var interrupted = false
    while (isAlive) {
        try { join() } catch (stop: InterruptedException) { interrupted = true }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private class Nest<E>(raise: Raise<E>, override val on: Executor) : Flock<E>, Raise<E> by raise {

    // Only the scope's own thread reaches this: a fork body is handed a nest of its own.
    private val forks = mutableListOf<Fork<E, *>>()

    override fun <T> async(on: Executor, start: Start, block: Flock<E>.() -> T): Deferred<T> {
        val fork = Fork(this, on, block, start = start)
        forks += fork
        return fork
    }

    /**
     * Interrupt is the only cancellation the JDK has, so every fork gets one before any of them is joined;
     * a fork whose turn on the executor has not come yet starts its body interrupted instead.
     */
    fun close() {
        forks.forEach { it.interrupt() }
        forks.forEach { it.join() }
    }

    fun unnoticedFailure(): Failure<E>? = forks.firstNotNullOfOrNull { it.unnoticedFailure() }
}

/**
 * The forks a combinator owns rather than the scope: it opens them, waits on the calling thread, and does
 * not return until every one has ended, so none of them is left for the scope to notice at close.
 */
internal class Flight<E>(private val owner: Raise<E>, private val on: Executor) {

    private val ended = Semaphore(0)
    private val forks = mutableListOf<Fork<E, *>>()

    fun <T> fork(block: Raise<E>.() -> T): Fork<E, T> = Fork(owner, on, block, ended::release).also { forks += it }

    /** Waits for every fork, or for the first of them to fail, then ends the rest and surfaces that failure. */
    fun settleAll() {
        settle { forks.none { fork -> fork.hasFailed() } }
        surfaceFirstFailure()
    }

    /** Waits for the first fork to answer, whatever it answers, then ends the rest and surfaces a failure. */
    fun settleFirst() {
        settle { forks.none { fork -> fork.hasEnded() } }
        surfaceFirstFailure()
    }

    /**
     * Waits for every fork to answer, or for the first of them to throw; a raise does not end a sibling here,
     * so the errors come back in start order for the caller to answer with together.
     */
    fun settleEvery(): List<E> {
        settle { forks.none { fork -> fork.hasThrown() } }
        forks.firstNotNullOfOrNull { it.ownThrow() }?.let { owner.surface(it) }
        return forks.mapNotNull { it.raisedError() }
    }

    private fun surfaceFirstFailure() {
        forks.firstNotNullOfOrNull { it.ownFailure() }?.let { owner.surface(it) }
    }

    private fun settle(waiting: () -> Boolean) {
        try {
            // Once the answer is in, the forks still running are interrupted below rather than waited out.
            repeat(forks.size) { if (waiting()) ended.acquire() }
        } finally {
            forks.forEach { it.interrupt() }
            forks.forEach { it.join() }
        }
    }
}

/**
 * Where a fork is in its life. One reference holds it and every move is a compare-and-set, so there is no
 * combination of flags to keep consistent: a stage either is the fork's state or it is not.
 */
private sealed interface Stage

/** A lazy fork nobody has asked for yet: nothing has reached the executor. */
private data object Unstarted : Stage

/** Handed to the executor, whose turn has not come; a cancel here makes the body start interrupted. */
private class Submitted(val cancelled: Boolean, val waiting: Waiter?) : Stage

private class Running(val thread: Thread, val waiting: Waiter?) : Stage

/**
 * A cancel has claimed the running thread and is sending the interrupt. The body cannot end until it is
 * sent, or the interrupt could land on whatever the executor lends that thread to next.
 */
private class Interrupting(val thread: Thread, val waiting: Waiter?) : Stage

private class Interrupted(val thread: Thread, val waiting: Waiter?) : Stage

/** The body answered, or the fork was cancelled before it ever ran. */
private class Ended(val ran: Boolean) : Stage

/** A thread parked until a fork ends, chained into a stack the stage carries from move to move. */
private class Waiter(val thread: Thread, val next: Waiter?)

private fun Stage.waiting(): Waiter? = when (this) {
    is Submitted -> waiting
    is Running -> waiting
    is Interrupting -> waiting
    is Interrupted -> waiting
    Unstarted, is Ended -> null
}

/** The same stage with [thread] added to its waiters; null where there is nothing left to wait for. */
private fun Stage.joinedBy(thread: Thread): Stage? = when (this) {
    is Submitted -> Submitted(cancelled, Waiter(thread, waiting))
    is Running -> Running(this.thread, Waiter(thread, waiting))
    is Interrupting -> Interrupting(this.thread, Waiter(thread, waiting))
    is Interrupted -> Interrupted(this.thread, Waiter(thread, waiting))
    Unstarted, is Ended -> null
}

internal class Fork<E, T>(
    private val owner: Raise<E>,
    private val on: Executor,
    private val block: Flock<E>.() -> T,
    private val ended: () -> Unit = {},
    start: Start = Start.Eager,
) : Deferred<T> {

    private val stage = AtomicReference<Stage>(Unstarted)

    // Written before the move to Ended, which is what every reader checks first.
    @Volatile
    private var outcome: Outcome<E, T>? = null

    @Volatile
    private var noticed = false

    // An interrupt sent to a fork that had not answered yet: what comes back is that interrupt rather than
    // anything the caller asked for, so a combinator drops it.
    @Volatile
    private var cutShort = false

    @Volatile
    private var cancelled = false

    // A fork on the default executor has a thread of its own, which `join` waits out as well, so a scope
    // that has returned has left nothing alive. A borrowed thread outlives the fork and cannot be joined.
    @Volatile
    private var ownThread: Thread? = null

    // Read on the opening thread rather than inside the task, which runs on a thread that bound none of it.
    private val inherited = Bindings.snapshot()

    init {
        if (start == Start.Eager) startOnce()
    }

    private fun startOnce() {
        if (stage.compareAndSet(Unstarted, Submitted(cancelled = false, waiting = null))) on.execute(::run)
    }

    private fun run() = Bindings.under(inherited) {
        val lent = Thread.currentThread()
        if (on === VirtualThreads) ownThread = lent
        claim(lent)
        var answer: Outcome<E, T> = Thrown(IllegalStateException("a fork's body left without an answer"))
        try {
            answer = capture(on, block)
        } finally {
            finish(answer)
        }
    }

    /** Submitted to Running, or to Interrupted where a cancel came first: the body then starts interrupted. */
    private fun claim(lent: Thread) {
        while (true) {
            val submitted = stage.get() as Submitted
            val next =
                if (submitted.cancelled) Interrupted(lent, submitted.waiting) else Running(lent, submitted.waiting)
            if (stage.compareAndSet(submitted, next)) {
                if (submitted.cancelled) lent.interrupt()
                return
            }
        }
    }

    private fun finish(answer: Outcome<E, T>) {
        outcome = answer
        var current = stage.get()
        while (current is Interrupting || !stage.compareAndSet(current, Ended(ran = true))) {
            if (current is Interrupting) Thread.onSpinWait()
            current = stage.get()
        }
        // An interrupt that arrived as the body left must not reach the executor's next task.
        Thread.interrupted()
        wake(current.waiting())
        ended()
    }

    override fun await(): T {
        startOnce()
        join()
        noticed = true
        return when (val settled = settled()) {
            is Returned -> settled.value
            is Failure -> owner.surface(settled)
        }
    }

    override fun cancel() {
        interrupt()
        join()
        noticed = true
    }

    fun interrupt() {
        var reached: Boolean?
        do {
            reached = interruptFrom(stage.get())
        } while (reached == null)
        // Close interrupts a fork `cancel` already stopped, and by then it has answered: only the first
        // interrupt says whether the fork was cut short.
        if (!cancelled) cutShort = reached
        cancelled = true
    }

    /**
     * Moves the fork on from [current] as a cancel does, and says whether that cut it short; null when the
     * stage moved first and the cancel has to look again.
     */
    private fun interruptFrom(current: Stage): Boolean? = when (current) {
        // Nothing is running to notice an interrupt and nothing will end the fork, so it answers here as
        // though it had been stopped at its first interruptible call.
        Unstarted -> {
            outcome = Thrown(InterruptedException("cancelled before it was started"))
            true.takeIf { stage.compareAndSet(current, Ended(ran = false)) }
        }

        is Submitted -> when {
            current.cancelled -> false
            stage.compareAndSet(current, Submitted(cancelled = true, waiting = current.waiting)) -> true
            else -> null
        }

        is Running -> interruptRunning(current.thread, current, current.waiting, cutShort = true)

        // A body that swallowed the first interrupt and blocked again gets another.
        is Interrupted -> interruptRunning(current.thread, current, current.waiting, cutShort = false)

        is Interrupting, is Ended -> false
    }

    private fun interruptRunning(thread: Thread, current: Stage, waiting: Waiter?, cutShort: Boolean): Boolean? {
        if (!stage.compareAndSet(current, Interrupting(thread, waiting))) return null
        deliver(thread)
        return cutShort
    }

    /** Sends the interrupt while the stage holds the body back from ending, then lets it end. */
    private fun deliver(thread: Thread) {
        thread.interrupt()
        while (true) {
            val interrupting = stage.get() as Interrupting
            if (stage.compareAndSet(interrupting, Interrupted(interrupting.thread, interrupting.waiting))) return
        }
    }

    fun join() {
        awaitEnded()
        ownThread?.joinFully()
    }

    /**
     * Parks until the fork has ended. An interrupt landing on the waiting thread must not leave the fork
     * running, so the wait goes on and the flag is handed back afterwards. A lazy fork never started has
     * nothing to wait for.
     */
    private fun awaitEnded() {
        val waiter = Thread.currentThread()
        var registered = false
        var interrupted = false
        while (true) {
            val current = stage.get()
            if (current === Unstarted || current is Ended) break
            if (!registered) {
                registered = stage.compareAndSet(current, checkNotNull(current.joinedBy(waiter)))
            } else {
                LockSupport.park(this)
                if (Thread.interrupted()) interrupted = true
            }
        }
        if (interrupted) waiter.interrupt()
    }

    private fun wake(waiting: Waiter?) {
        var next = waiting
        while (next != null) {
            LockSupport.unpark(next.thread)
            next = next.next
        }
    }

    fun hasFailed(): Boolean = hasEnded() && outcome is Failure

    fun hasEnded(): Boolean = stage.get() is Ended

    fun hasThrown(): Boolean = hasEnded() && outcome is Thrown

    /** Whether this fork answered of its own accord, rather than being cut short by a combinator's interrupt. */
    fun answered(): Boolean = !cutShort

    /** The failure this fork answered with of its own accord, rather than the interrupt a combinator sent it. */
    fun ownFailure(): Failure<E>? = (settled() as? Failure<E>)?.takeUnless { cutShort && it.isInterrupt() }

    /** The throwable this fork threw of its own accord: a throw ends an accumulating combinator's siblings. */
    fun ownThrow(): Thrown? = ownFailure() as? Thrown

    /** The error this fork raised of its own accord, for the combinator that accumulates rather than surfaces. */
    fun raisedError(): E? = when (val failure = ownFailure()) {
        is Raised -> failure.error
        is Thrown -> null
        null -> null
    }

    /** The failure of a fork whose outcome nobody asked for; one that never ran has none to answer with. */
    fun unnoticedFailure(): Failure<E>? {
        val current = stage.get()
        if (current !is Ended || !current.ran) return null
        return settled().takeUnless { noticed } as? Failure<E>
    }

    private fun settled(): Outcome<E, T> = checkNotNull(outcome) { "a joined fork always has an outcome" }
}

internal sealed interface Outcome<out E, out T>

internal class Returned<T>(val value: T) : Outcome<Nothing, T>

internal sealed interface Failure<out E> : Outcome<E, Nothing>

internal class Raised<E>(val error: E) : Failure<E>

internal class Thrown(val throwable: Throwable) : Failure<Nothing>
