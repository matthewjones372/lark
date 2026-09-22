package io.github.matthewjones372.lark.structured

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.matthewjones372.lark.Deferred
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.Start
import io.github.matthewjones372.lark.VirtualThreads
import java.util.concurrent.Executor
import java.util.concurrent.StructuredTaskScope
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A `Flock` whose forks are subtasks of a JDK `StructuredTaskScope`: a thread dump shows them under [name],
 * they inherit the caller's `ScopedValue` bindings, and the JDK refuses a scope closed out of order.
 */
fun <E, A> structured(name: String = "structured", block: Flock<E>.() -> A): Either<E, A> =
    either { structured(name, block) }

/** The same scope inside a `Raise` already in hand. */
fun <E, A> Raise<E>.structured(name: String = "structured", block: Flock<E>.() -> A): A =
    runScope(this, name, null, block)

/**
 * A scope whose forks have a deadline: when [timeout] passes the JDK cancels them, and the scope answers
 * [onTimeout] instead of an exception. The block itself is not interrupted, only the forks it waits on.
 */
fun <E, A> structured(
    name: String = "structured",
    timeout: Duration,
    onTimeout: () -> E,
    block: Flock<E>.() -> A,
): Either<E, A> = either { structured(name, timeout, onTimeout, block) }

/** The same, inside a `Raise` already in hand. */
fun <E, A> Raise<E>.structured(
    name: String = "structured",
    timeout: Duration,
    onTimeout: () -> E,
    block: Flock<E>.() -> A,
): A = runScope(this, name, Deadline(timeout, onTimeout), block)

internal class Deadline<E>(val after: Duration, val error: () -> E)

internal fun <E, A> runScope(raise: Raise<E>, name: String, deadline: Deadline<E>?, block: Flock<E>.() -> A): A {
    val scope = Scope(raise, name, deadline)
    val value = try {
        scope.block()
    } finally {
        scope.close()
    }
    // Only reached on a return, so a fork nobody awaited still gets to fail its parent.
    scope.answer()
    return value
}

internal class Scope<E>(raise: Raise<E>, private val name: String, private val deadline: Deadline<E>?) :
    Flock<E>,
    Raise<E> by raise {

    private val owner = Thread.currentThread()
    private val joiner = CancelOnClose()

    // Only the owner touches these: a fork's body is handed a scope of its own.
    private val forks = mutableListOf<Fork<E, *>>()
    private var forking: Fork<E, *>? = null
    private var expired = false

    private val tasks: StructuredTaskScope<Any?, Boolean, RuntimeException> =
        StructuredTaskScope.open(joiner) { config ->
            val named = config.withName(name).withThreadFactory(::newThread)
            if (deadline == null) named else named.withTimeout(deadline.after.toJavaDuration())
        }

    override val on: Executor get() = VirtualThreads

    override fun <T> async(on: Executor, start: Start, block: Flock<E>.() -> T): Deferred<T> {
        require(on === VirtualThreads) {
            "structured forks one virtual thread per subtask; use flock to fork onto an executor"
        }
        checkOwner()
        val fork = Fork(this, "$name/${forks.size + 1}", block)
        forks += fork
        if (start == Start.Eager) fork.start()
        return fork
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

    fun expire(): Nothing = raise(checkNotNull(deadline) { "a scope with no deadline cannot expire" }.error())

    fun close() {
        // A fork still running now was cut short by the close, and its interrupt is not its answer.
        forks.filterNot { it.hasEnded() }.forEach { it.markCutShort() }
        joiner.closing = true
        var interrupted = false
        try {
            // The joiner cancels the scope on this fork, which interrupts every subtask still running.
            tasks.fork<Any?>(Runnable {})
            expired = tasks.join()
        } catch (stop: InterruptedException) {
            interrupted = true
        } finally {
            tasks.close()
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    fun answer() {
        if (expired) expire()
        forks.firstNotNullOfOrNull { it.unnoticedFailure() }?.let { surface(it) }
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
