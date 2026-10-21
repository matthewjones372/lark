package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.VirtualThreads
import java.util.concurrent.ArrayBlockingQueue

/** How the stream a buffer holds for ended: marked in the queue after its last element. */
private sealed interface Ended {
    data object Done : Ended

    class Threw(val thrown: Throwable) : Ended
}

/**
 * Upstream on a fork of its own, into a queue of [Node.Buffer.size]: it runs ahead until the queue is
 * full, and the pull takes from the queue. What upstream ended with arrives after its last element. The
 * run interrupts the fork and waits for it when it ends, however it ends.
 */
// The catch is as wide as a pipeline: whatever upstream threw is the pull's to throw, in order.
@Suppress("TooGenericExceptionCaught")
internal fun Node.Buffer.buffered(releases: Releases?): Pull {
    val up = upstream.pull()
    val queue = ArrayBlockingQueue<Any>(size)
    val fork = InFlight(releases?.on ?: VirtualThreads) {
        try {
            Releases.within(releases) {
                generateSequence { up.next() }.forEach { a -> queue.put(a) }
                queue.put(Ended.Done)
            }
        } catch (_: InterruptedException) {
            // Let go of by the run: nothing reads what would have come next.
        } catch (thrown: Throwable) {
            // Waits for room like an element: the pull takes what came before it first, and the run
            // interrupts this if nobody will.
            try {
                queue.put(Ended.Threw(thrown))
            } catch (_: InterruptedException) {
                // The run let go while this waited: nobody reads the failure either.
            }
        }
    }
    releases?.add(fork::cancel)
    var ended = false
    return Pull {
        if (ended) {
            null
        } else {
            when (val next = queue.take()) {
                Ended.Done -> null.also { ended = true }
                is Ended.Threw -> throw next.thrown.also { ended = true }
                else -> next
            }
        }
    }
}

/**
 * The same on a test's clock: upstream is a worker of its own, which fills the buffer in its turns and
 * parks while it is full, and the pull parks while it is empty.
 */
// The catch is as wide as a pipeline, as above.
@Suppress("TooGenericExceptionCaught")
internal fun Node.Buffer.bufferedOnClock(turns: Turns): Pull {
    val up = upstream.pull()
    val held = ArrayDeque<Any>()
    turns.fork {
        try {
            while (!turns.stopped) {
                val a = up.next()
                held.addLast(a ?: Ended.Done)
                if (a == null) break
                turns.park { held.size < size }
            }
        } catch (thrown: Throwable) {
            held.addLast(Ended.Threw(thrown))
        }
    }
    return Pull {
        while (held.isEmpty() && !turns.stopped) turns.park { held.isNotEmpty() }
        when (val next = held.firstOrNull()) {
            null, Ended.Done -> null
            is Ended.Threw -> throw next.thrown
            else -> held.removeFirst()
        }
    }
}
