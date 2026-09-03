package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import java.util.concurrent.Semaphore

/** A `Raise` scope that owns every thread forked in it: no fork outlives the block that opened it. */
interface Flock<E> : Raise<E> {
    /** Forks a virtual thread running [block] under a nested scope of its own. */
    fun <T> async(block: Flock<E>.() -> T): Deferred<T>
}

/**
 * Opens a scope on the calling thread and closes it when [block] leaves — by return, raise or throw —
 * interrupting every fork still running and joining all of them before this returns.
 */
fun <E, A> flock(block: Flock<E>.() -> A): Either<E, A> = either { flock(block) }

/**
 * The same scope inside a `Raise` already in hand, so `either { flock { async { } } }` needs no second
 * boundary; a fork still opens one of its own, because a `Raise` never crosses a thread.
 */
fun <E, A> Raise<E>.flock(block: Flock<E>.() -> A): A {
    val nest = Nest(this)
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
private fun <E, T> capture(block: Flock<E>.() -> T): Outcome<E, T> =
    try {
        either { flock(block) }.fold({ Raised(it) }, { Returned(it) })
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

private class Nest<E>(raise: Raise<E>) : Flock<E>, Raise<E> by raise {

    // Only the scope's own thread reaches this: a fork body is handed a nest of its own.
    private val forks = mutableListOf<Fork<E, *>>()

    override fun <T> async(block: Flock<E>.() -> T): Deferred<T> {
        val fork = Fork(this, block)
        forks += fork
        return fork
    }

    /** Interrupt is the only cancellation the JDK has, so every fork gets one before any of them is joined. */
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
internal class Flight<E>(private val owner: Raise<E>) {

    private val ended = Semaphore(0)
    private val forks = mutableListOf<Fork<E, *>>()

    fun <T> fork(block: Raise<E>.() -> T): Fork<E, T> = Fork(owner, block, ended::release).also { forks += it }

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
    block: Flock<E>.() -> T,
    ended: () -> Unit = {},
) : Deferred<T> {

    @Volatile
    private var outcome: Outcome<E, T>? = null

    @Volatile
    private var noticed = false

    // An interrupt sent to a fork that had not answered yet: what comes back is that interrupt rather than
    // anything the caller asked for, so a combinator drops it.
    @Volatile
    private var cutShort = false

    private val thread: Thread = Thread.ofVirtual().start {
        outcome = capture(block)
        ended()
    }

    override fun await(): T {
        thread.joinFully()
        noticed = true
        return when (val settled = settled()) {
            is Returned -> settled.value
            is Failure -> owner.surface(settled)
        }
    }

    fun interrupt() {
        cutShort = outcome == null
        thread.interrupt()
    }

    fun join(): Unit = thread.joinFully()

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

    /** The failure of a fork whose outcome nobody asked for; the scope answers with it at close. */
    fun unnoticedFailure(): Failure<E>? = settled().takeUnless { noticed } as? Failure<E>

    private fun settled(): Outcome<E, T> = checkNotNull(outcome) { "a joined fork always has an outcome" }
}

internal sealed interface Outcome<out E, out T>

internal class Returned<T>(val value: T) : Outcome<Nothing, T>

internal sealed interface Failure<out E> : Outcome<E, Nothing>

internal class Raised<E>(val error: E) : Failure<E>

internal class Thrown(val throwable: Throwable) : Failure<Nothing>
