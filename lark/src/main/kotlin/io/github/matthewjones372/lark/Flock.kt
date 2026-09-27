package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    /**
     * Runs [hook] on the closing thread when this scope starts to close, before any fork is interrupted, so the
     * scope's forks are still running for it: to leave a cluster, or to wait for work in flight to land. Hooks run
     * in reverse order of registration, as a `resourceScope` releases. A hook that throws is logged and the scope
     * closes all the same; one interrupted waiting ends, the rest still run, and the interrupt is kept for after.
     */
    fun onClose(hook: () -> Unit)
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

/** The same retry, waiting on a fork's own completion rather than on a thread the executor lent it. */
private fun CountDownLatch.awaitFully() {
    var interrupted = false
    while (count > 0L) {
        try { await() } catch (stop: InterruptedException) { interrupted = true }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private class Nest<E>(raise: Raise<E>, override val on: Executor) : Flock<E>, Raise<E> by raise {

    // Only the scope's own thread reaches this: a fork body is handed a nest of its own.
    private val forks = mutableListOf<Fork<E, *>>()

    // Registered from any thread that holds the scope, such as an actor started in it.
    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    override fun onClose(hook: () -> Unit) {
        hooks += hook
    }

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
        // A hook that throws an Error still leaves no fork running: it is thrown once every one has ended.
        try {
            runHooks()
        } finally {
            forks.forEach { it.interrupt() }
            forks.forEach { it.join() }
        }
    }

    fun unnoticedFailure(): Failure<E>? = forks.firstNotNullOfOrNull { it.unnoticedFailure() }

    private fun runHooks() {
        var interrupted = false
        hooks.reversed().forEach { hook ->
            try {
                hook()
            } catch (stop: InterruptedException) {
                interrupted = true
            } catch (thrown: Exception) {
                logError("a close hook failed; the flock closes all the same", thrown)
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
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

internal class Fork<E, T>(
    private val owner: Raise<E>,
    private val on: Executor,
    private val block: Flock<E>.() -> T,
    private val ended: () -> Unit = {},
    start: Start = Start.Eager,
) : Deferred<T> {

    @Volatile
    private var outcome: Outcome<E, T>? = null

    @Volatile
    private var noticed = false

    // An interrupt sent to a fork that had not answered yet: what comes back is that interrupt rather than
    // anything the caller asked for, so a combinator drops it.
    @Volatile
    private var cutShort = false

    // An executor hands back no handle on the thread it runs a task on, so the body records the one it is
    // lent. That reference and the cancel aimed at it move under one lock: read outside it, an interrupt
    // could land on a thread the body had already given back, and so on whatever the executor ran next.
    private val cancelling = ReentrantLock()
    private var borrowed: Thread? = null
    private var cancelled = false

    // A fork on the default executor has a thread of its own, which `join` waits out as well, so a scope
    // that has returned has left nothing alive. A borrowed thread outlives the fork and cannot be joined.
    @Volatile
    private var ownThread: Thread? = null

    private val finished = CountDownLatch(1)

    // Read on the opening thread rather than inside the task, which runs on a thread that bound none of it.
    private val inherited = Bindings.snapshot()

    // Whether the body has reached the executor. A lazy fork nobody asked for never does, and then there is
    // no thread to interrupt and no latch anything will ever count down.
    @Volatile
    private var started = false

    init {
        if (start == Start.Eager) submit()
    }

    private fun submit() {
        started = true
        on.execute {
            Bindings.under(inherited) {
                cancelling.withLock {
                    borrowed = Thread.currentThread()
                    if (on === VirtualThreads) ownThread = Thread.currentThread()
                    // Cancelled before its turn on the executor came: the body starts interrupted rather
                    // than running on as though the scope it belonged to were still open.
                    if (cancelled) Thread.currentThread().interrupt()
                }
                try {
                    outcome = capture(on, block)
                } finally {
                    cancelling.withLock {
                        borrowed = null
                        Thread.interrupted()
                    }
                    finished.countDown()
                    ended()
                }
            }
        }
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

    private fun startOnce() {
        if (cancelling.withLock { !started && !cancelled }) submit()
    }

    override fun cancel() {
        interrupt()
        join()
        noticed = true
    }

    fun interrupt() {
        cancelling.withLock {
            // Close interrupts a fork `cancel` already stopped, and by then it has answered: reading
            // `outcome` a second time would call it a fork that failed of its own accord.
            if (!cancelled) cutShort = outcome == null
            cancelled = true
            // Nothing is running to notice an interrupt and nothing will end the fork, so it answers here
            // as though it had been stopped at its first interruptible call.
            if (!started && outcome == null) {
                outcome = Thrown(InterruptedException("cancelled before it was started"))
                finished.countDown()
            }
            borrowed?.interrupt()
        }
    }

    fun join() {
        if (!started) return
        finished.awaitFully()
        ownThread?.joinFully()
    }

    fun hasFailed(): Boolean = outcome is Failure

    fun hasEnded(): Boolean = outcome != null

    fun hasThrown(): Boolean = outcome is Thrown

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
    fun unnoticedFailure(): Failure<E>? = if (!started) null else settled().takeUnless { noticed } as? Failure<E>

    private fun settled(): Outcome<E, T> = checkNotNull(outcome) { "a joined fork always has an outcome" }
}

internal sealed interface Outcome<out E, out T>

internal class Returned<T>(val value: T) : Outcome<Nothing, T>

internal sealed interface Failure<out E> : Outcome<E, Nothing>

internal class Raised<E>(val error: E) : Failure<E>

internal class Thrown(val throwable: Throwable) : Failure<Nothing>
