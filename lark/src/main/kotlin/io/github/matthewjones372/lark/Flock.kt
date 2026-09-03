package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either

/** A `Raise` scope that owns every thread forked in it: no fork outlives the block that opened it. */
interface Flock<E> : Raise<E> {
    /** Forks a virtual thread running [block] under a nested scope of its own. */
    fun <T> async(block: Flock<E>.() -> T): Deferred<T>
}

/**
 * Opens a scope on the calling thread and closes it when [block] leaves — by return, raise or throw —
 * interrupting every fork still running and joining all of them before this returns.
 */
fun <E, A> flock(block: Flock<E>.() -> A): Either<E, A> = either { owning(block) }

/**
 * Arrow's `either` is the boundary on whichever thread this runs on, so a `Raise` never crosses one:
 * a fork opens its own boundary and hands back an outcome instead.
 */
private fun <E, A> Raise<E>.owning(block: Flock<E>.() -> A): A {
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
        either { owning(block) }.fold({ Raised(it) }, { Returned(it) })
    } catch (t: Throwable) {
        Thrown(t)
    }

private fun <E> Raise<E>.surface(failure: Failure<E>): Nothing = when (failure) {
    is Raised -> raise(failure.error)
    is Thrown -> throw failure.throwable
}

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

private class Fork<E, T>(private val owner: Flock<E>, block: Flock<E>.() -> T) : Deferred<T> {

    @Volatile
    private var outcome: Outcome<E, T>? = null

    @Volatile
    private var noticed = false

    private val thread: Thread = Thread.ofVirtual().start { outcome = capture(block) }

    override fun await(): T {
        thread.joinFully()
        noticed = true
        return when (val settled = settled()) {
            is Returned -> settled.value
            is Failure -> owner.surface(settled)
        }
    }

    fun interrupt(): Unit = thread.interrupt()

    fun join(): Unit = thread.joinFully()

    /** The failure of a fork whose outcome nobody asked for; the scope answers with it at close. */
    fun unnoticedFailure(): Failure<E>? = settled().takeUnless { noticed } as? Failure<E>

    private fun settled(): Outcome<E, T> = checkNotNull(outcome) { "a joined fork always has an outcome" }
}

private sealed interface Outcome<out E, out T>

private class Returned<T>(val value: T) : Outcome<Nothing, T>

private sealed interface Failure<out E> : Outcome<E, Nothing>

private class Raised<E>(val error: E) : Failure<E>

private class Thrown(val throwable: Throwable) : Failure<Nothing>
