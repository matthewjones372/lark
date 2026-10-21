package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.VirtualThreads
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore

/** How the stream a feeding fork read ended: marked in its queue after the last thing it started. */
internal sealed interface Fed {
    data object Done : Fed

    class Threw(val thrown: Throwable) : Fed
}

/** An element and the stage the caller started for it. */
private class Started(val element: Any, val stage: CompletableFuture<Any?>)

/**
 * Up to [window] of the caller's stages at once, answered in the order the elements came. Upstream is on
 * a fork of its own, which starts each stage as its element arrives, so a stage that is done is passed on
 * even while upstream waits for the next element: a source that blocks, a Kafka consumer on a quiet topic
 * among them, waits for as long as it is quiet. A stage that fails is the defect `mapAsync` names, and one
 * that completes with `null` is too, as on Pekko. The run interrupts the fork and waits for it when it ends.
 */
internal fun Node.MapAsync.fed(window: Int, releases: Releases?): Pull {
    val start = guarded("mapAsync", at, f)
    val up = upstream.pull()
    val room = Semaphore(window)
    val started = LinkedBlockingQueue<Any>()
    val fork = InFlight(releases?.on ?: VirtualThreads) {
        Releases.within(releases) {
            feed(up, room, started) { a ->
                @Suppress("UNCHECKED_CAST")
                Started(a, (start(a) as CompletionStage<Any?>).toCompletableFuture())
            }
        }
    }
    releases?.add(fork::cancel)
    var ended = false
    return Pull {
        if (ended) return@Pull null
        when (val next = started.take()) {
            Fed.Done -> null.also { ended = true }
            is Fed.Threw -> throw next.thrown.also { ended = true }
            else -> answered(next as Started).also { room.release() }
        }
    }
}

/**
 * Upstream's elements, each started by [start] once there is [room] for it and queued in order, then how
 * upstream ended. `offer` rather than `put`: the queue has no bound, and a put an interrupt cut short would
 * lose what was started, which then nothing would let go of.
 */
// The catch is as wide as a pipeline: whatever upstream threw is the pull's to throw, after what came first.
@Suppress("TooGenericExceptionCaught")
internal fun feed(up: Pull, room: Semaphore, started: BlockingQueue<Any>, start: (Any) -> Any) {
    try {
        while (true) {
            room.acquire()
            val a = up.next() ?: break
            started.offer(start(a))
        }
        started.offer(Fed.Done)
    } catch (_: InterruptedException) {
        // Let go of by the run: nothing reads what would have come next.
    } catch (thrown: Throwable) {
        started.offer(Fed.Threw(thrown))
    }
}

/** The stage's value, waited on, or the defect it failed with. */
private fun Node.MapAsync.answered(next: Started): Any {
    val b = try {
        next.stage.get()
    } catch (failed: ExecutionException) {
        throw (failed.cause ?: failed).unwrapped().describedBy("mapAsync", next.element, at)
    }
    return b ?: throw NullPointerException("${facts("mapAsync", next.element, at)}: the stage completed with null")
}
