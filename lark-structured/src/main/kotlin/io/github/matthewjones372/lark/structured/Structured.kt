package io.github.matthewjones372.lark.structured

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.matthewjones372.lark.Deferred
import java.util.concurrent.Semaphore
import java.util.concurrent.StructuredTaskScope
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The block of a [structured] scope. Every fork waits to be asked: `await` runs one, and [awaitAll] runs
 * several at once. A fork nobody awaits never runs, and no fork runs while the block itself does.
 */
interface StructuredScope<E> : Raise<E> {

    /** A fork that runs nothing until it is awaited, under a scope of its own named after it. */
    fun <T> async(block: StructuredScope<E>.() -> T): Deferred<T>

    /** Starts every fork not yet started, together, and answers in order; the first fork to fail is the answer. */
    fun <T> awaitAll(forks: List<Deferred<T>>): List<T>

    /** [awaitAll] for two forks of different types. */
    fun <A, B> awaitAll(a: Deferred<A>, b: Deferred<B>): Pair<A, B>

    /** [awaitAll] for three forks of different types. */
    fun <A, B, C> awaitAll(a: Deferred<A>, b: Deferred<B>, c: Deferred<C>): Triple<A, B, C>
}

/**
 * Opens a scope over a JDK `StructuredTaskScope`: a thread dump shows its forks under [name], they inherit
 * the caller's `ScopedValue` bindings, and the JDK refuses a scope closed out of order.
 */
fun <E, A> structured(name: String = "structured", block: StructuredScope<E>.() -> A): Either<E, A> =
    either { structured(name, block) }

/** The same scope inside a `Raise` already in hand. */
fun <E, A> Raise<E>.structured(name: String = "structured", block: StructuredScope<E>.() -> A): A =
    runScope(this, name, null, block)

/**
 * A scope whose forks have a deadline: when [timeout] passes the JDK cancels them, and the scope answers
 * [onTimeout] instead of an exception.
 */
fun <E, A> structured(
    name: String = "structured",
    timeout: Duration,
    onTimeout: () -> E,
    block: StructuredScope<E>.() -> A,
): Either<E, A> = either { structured(name, timeout, onTimeout, block) }

/** The same, inside a `Raise` already in hand. */
fun <E, A> Raise<E>.structured(
    name: String = "structured",
    timeout: Duration,
    onTimeout: () -> E,
    block: StructuredScope<E>.() -> A,
): A = runScope(this, name, Deadline(timeout, onTimeout), block)

internal class Deadline<E>(val after: Duration, val error: () -> E)

internal fun <E, A> runScope(
    raise: Raise<E>,
    name: String,
    deadline: Deadline<E>?,
    block: StructuredScope<E>.() -> A,
): A {
    val scope = Scope(raise, name, deadline)
    val value = try {
        scope.block()
    } finally {
        scope.close()
    }
    // Only reached on a return: a block that overran its deadline answers with it all the same.
    if (scope.expiredBeforeClose()) scope.expire()
    return value
}

internal class Scope<E>(raise: Raise<E>, private val name: String, private val deadline: Deadline<E>?) :
    StructuredScope<E>,
    Raise<E> by raise {

    private val owner = Thread.currentThread()
    private val joiner = CancelOnClose()

    // Only the owner touches these: a fork's body is handed a scope of its own.
    private var forked = 0
    private var forking: Fork<E, *>? = null
    private var expired = false

    private val tasks: StructuredTaskScope<Any?, Boolean, RuntimeException> =
        StructuredTaskScope.open(joiner) { config ->
            val named = config.withName(name).withThreadFactory(::newThread)
            if (deadline == null) named else named.withTimeout(deadline.after.toJavaDuration())
        }

    override fun <T> async(block: StructuredScope<E>.() -> T): Deferred<T> {
        checkOwner()
        forked += 1
        return Fork(this, "$name/$forked", block)
    }

    override fun <T> awaitAll(forks: List<Deferred<T>>): List<T> {
        settleAll(forks.map(::own))
        return forks.map { it.await() }
    }

    override fun <A, B> awaitAll(a: Deferred<A>, b: Deferred<B>): Pair<A, B> {
        settleAll(listOf(own(a), own(b)))
        return a.await() to b.await()
    }

    override fun <A, B, C> awaitAll(a: Deferred<A>, b: Deferred<B>, c: Deferred<C>): Triple<A, B, C> {
        settleAll(listOf(own(a), own(b), own(c)))
        return Triple(a.await(), b.await(), c.await())
    }

    /** Starts every fork, then waits until all have ended or one has failed, which its `await` then surfaces. */
    private fun settleAll(forks: List<Fork<E, *>>) {
        checkOwner()
        forks.forEach { it.start() }
        val ended = Semaphore(0)
        forks.forEach { it.whenEnded(ended::release) }
        var running = forks.size
        while (running > 0 && forks.none { it.hasFailed() }) {
            // As `await` does: an interrupt on the owner does not leave it short of an answer.
            ended.acquireUninterruptibly()
            running -= 1
        }
        forks.firstOrNull { it.hasFailed() }?.await()
    }

    private fun own(fork: Deferred<*>): Fork<E, *> {
        require(fork is Fork<*, *> && fork.belongsTo(this)) { "awaitAll takes forks of the scope it is called in" }
        @Suppress("UNCHECKED_CAST") // Its scope is this one, so its error type is this scope's.
        return fork as Fork<E, *>
    }

    fun checkOwner() {
        if (Thread.currentThread() !== owner) {
            throw WrongThreadException("a fork of '$name' is awaited and cancelled only by the thread that opened it")
        }
    }

    /**
     * Forks [fork] as a subtask, and says whether a thread will ever run it. A scope a deadline has
     * cancelled starts none, and the JDK says so only by never calling the thread factory, or by leaving
     * the thread it made unstarted.
     */
    fun submit(fork: Fork<E, *>): Boolean {
        forking = fork
        try {
            tasks.fork<Any?>(Runnable(fork::run))
        } finally {
            forking = null
        }
        return fork.thread?.let { it.state != Thread.State.NEW } ?: false
    }

    // `fork` calls this on the owner's thread before it returns, which is how a fork learns its thread.
    private fun newThread(task: Runnable): Thread {
        val fork = checkNotNull(forking) { "only a fork of '$name' starts a thread in it" }
        return Thread.ofVirtual().name(fork.name).unstarted(task).also { fork.thread = it }
    }

    /** Cancelled while the block still runs: nothing but the deadline cancels this scope before it closes. */
    fun hasExpired(): Boolean = tasks.isCancelled && !joiner.closing

    fun expiredBeforeClose(): Boolean = expired

    fun expire(): Nothing = raise(checkNotNull(deadline) { "a scope with no deadline cannot expire" }.error())

    fun close() {
        joiner.closing = true
        var interrupted = false
        try {
            // The joiner cancels the scope on this fork, which interrupts any fork a raise left running.
            tasks.fork<Any?>(Runnable {})
            expired = tasks.join()
        } catch (stop: InterruptedException) {
            interrupted = true
        } finally {
            tasks.close()
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/** Cancels the scope when it closes, and tells `join` apart from a deadline that got there first. */
private class CancelOnClose : StructuredTaskScope.Joiner<Any?, Boolean, RuntimeException> {

    @Volatile
    var closing = false

    override fun onFork(subtask: StructuredTaskScope.Subtask<Any?>): Boolean = closing

    override fun result(): Boolean = false

    override fun timeout(): Boolean = true
}
