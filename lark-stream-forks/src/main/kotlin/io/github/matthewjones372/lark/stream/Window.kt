package io.github.matthewjones372.lark.stream

import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.VirtualThreads
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

/**
 * What a run has to let go of when it ends, however it ends: the bodies still in flight and the forks
 * still filling a buffer. Released in reverse, on the thread that ran the loop, before the exit completes.
 *
 * [on] is where the run starts every thread it starts for itself, so that whoever gave the backend its
 * executor sees them all.
 */
internal class Releases(val on: Executor) {

    /** Where a body given [asked] runs: the run's executor, unless the caller named one of its own. */
    fun executorFor(asked: Executor): Executor = if (asked === VirtualThreads) on else asked

    private val each = ArrayDeque<() -> Unit>()

    fun add(release: () -> Unit) = synchronized(each) { each.addFirst(release) }

    /**
     * Lets go of everything, each on its own: one that throws does not stop the rest, and what it threw
     * rides on the first. A stop's interrupt is spent by then, so no wait here is cut short by it.
     */
    // The catch is as wide as a release: whatever one threw, the others still run.
    @Suppress("TooGenericExceptionCaught")
    fun releaseAll() {
        Thread.interrupted()
        val failures = synchronized(each) { each.toList().also { each.clear() } }.mapNotNull { release ->
            try {
                release()
                null
            } catch (thrown: Throwable) {
                thrown
            }
        }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    companion object {
        private val current = ThreadLocal<Releases>()

        /** Binds [releases] for the pulls [block] builds on this thread, and releases them after it. */
        fun <A> around(releases: Releases, block: () -> A): A {
            current.set(releases)
            return try {
                block()
            } finally {
                current.remove()
                releases.releaseAll()
            }
        }

        /** The run's releases, or none: a pull built off a Forks run has nothing to register with. */
        fun here(): Releases? = current.get()

        /** Runs [block] on a fork of the run with the run's [releases] bound, which the run lets go of itself. */
        fun <A> within(releases: Releases?, block: () -> A): A {
            if (releases == null) return block()
            current.set(releases)
            return try {
                block()
            } finally {
                current.remove()
            }
        }
    }
}

/** One body on its own thread: its answer, and a way to interrupt it and wait until it has stopped. */
// The catch is as wide as a body: whatever it threw is the answer the pull loop rethrows.
@Suppress("TooGenericExceptionCaught")
internal class InFlight<T>(on: Executor, body: () -> T) {

    private val answer = CompletableFuture<T>()
    private val stopped = CountDownLatch(1)
    private var runner: Thread? = null
    private var cancelled = false

    init {
        on.execute {
            val going = synchronized(this) { (!cancelled).also { if (it) runner = Thread.currentThread() } }
            try {
                if (going) answer.complete(body()) else answer.cancel(false)
            } catch (thrown: Throwable) {
                answer.completeExceptionally(thrown)
            } finally {
                synchronized(this) { runner = null }
                stopped.countDown()
            }
        }
    }

    /** The body's answer, or what it threw, as it threw it. Interruptible, so that a stop wakes the loop. */
    fun await(): T =
        try {
            answer.get()
        } catch (wrapped: ExecutionException) {
            throw wrapped.cause ?: wrapped
        }

    /**
     * Interrupts the body if it is running, stops it running if it has not started, and waits for it to
     * have stopped, whatever interrupts the wait: a fork is let go of only once it is gone.
     */
    fun cancel() {
        synchronized(this) {
            cancelled = true
            runner?.interrupt()
        }
        var interrupted = false
        while (true) {
            try {
                stopped.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/**
 * Up to [Node.MapPar.parallelism] bodies at once, each on the node's executor (the run's, where the node
 * was left with the default), answered in the order the
 * elements came. Upstream is pulled only on the pulling thread, and only while the window has room, so
 * the stages above stay on one thread. The window is let go of when the run ends, and a failure it
 * answers with interrupts the bodies behind it.
 */
// The catch is as wide as a body's, and rethrows what it caught once the window behind it is let go of.
@Suppress("TooGenericExceptionCaught")
internal fun Node.MapPar.window(releases: Releases?): Pull {
    val body = guarded("mapPar", at) { a: Any -> either { f(a) } }
    val up = upstream.pull()
    val window = ArrayDeque<InFlight<Either<Any?, Any>>>()
    val executor = releases?.executorFor(on) ?: on
    val cancelAll = { generateSequence { window.removeFirstOrNull() }.forEach { it.cancel() } }
    releases?.add(cancelAll)
    var drained = false
    return Pull {
        while (!drained && window.size < parallelism) {
            val a = up.next()
            if (a == null) drained = true else window.addLast(InFlight(executor) { body(a) })
        }
        window.removeFirstOrNull()?.let { head ->
            val answer = try {
                head.await()
            } catch (thrown: Throwable) {
                // The head is out of the window by now, so it is let go of here, with the rest.
                head.cancel()
                cancelAll()
                throw thrown
            }
            answer.fold({ e -> cancelAll(); throw DeclaredFailure(e) }, { b -> b })
        }
    }
}
