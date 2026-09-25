package io.github.matthewjones372.lark.stream

import arrow.core.Either
import arrow.core.raise.either
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/**
 * What a run has to let go of when it ends, however it ends: the bodies still in flight and the forks
 * still filling a buffer. Released in reverse, on the thread that ran the loop, before the exit completes.
 */
internal class Releases {

    private val each = ArrayDeque<() -> Unit>()

    fun add(release: () -> Unit) = synchronized(each) { each.addFirst(release) }

    fun releaseAll() = synchronized(each) { each.toList().also { each.clear() } }.forEach { it() }

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
private class InFlight(on: Executor, body: () -> Either<Any?, Any>) {

    private val answer = CompletableFuture<Either<Any?, Any>>()
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

    /** The body's answer, or what it threw, as it threw it. */
    fun await(): Either<Any?, Any> =
        try {
            answer.join()
        } catch (wrapped: CompletionException) {
            throw wrapped.cause ?: wrapped
        }

    /** Interrupts the body if it is running, stops it running if it has not started, and waits for it. */
    fun cancel() {
        synchronized(this) {
            cancelled = true
            runner?.interrupt()
        }
        stopped.await()
    }
}

/**
 * Up to [Node.MapPar.parallelism] bodies at once, each on the node's executor, answered in the order the
 * elements came. Upstream is pulled only on the pulling thread, and only while the window has room, so
 * the stages above stay on one thread. The window is let go of when the run ends, and a failure it
 * answers with interrupts the bodies behind it.
 */
// The catch is as wide as a body's, and rethrows what it caught once the window behind it is let go of.
@Suppress("TooGenericExceptionCaught")
internal fun Node.MapPar.window(releases: Releases?): Pull {
    val body = guarded("mapPar", at) { a: Any -> either { f(a) } }
    val up = upstream.pull()
    val window = ArrayDeque<InFlight>()
    val cancelAll = { generateSequence { window.removeFirstOrNull() }.forEach(InFlight::cancel) }
    releases?.add(cancelAll)
    var drained = false
    return Pull {
        while (!drained && window.size < parallelism) {
            val a = up.next()
            if (a == null) drained = true else window.addLast(InFlight(on) { body(a) })
        }
        window.removeFirstOrNull()?.let { head ->
            val answer = try {
                head.await()
            } catch (thrown: Throwable) {
                cancelAll()
                throw thrown
            }
            answer.fold({ e -> cancelAll(); throw DeclaredFailure(e) }, { b -> b })
        }
    }
}
