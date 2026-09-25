package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.VirtualThreads
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

/** How many elements the inputs of a merge may hold between them before each waits for the reader. */
private const val ROOM = 16

/** What an input puts after its last element, or in place of the rest when it failed. */
private sealed interface Signal {
    /** An input has ended. */
    data object Finished : Signal

    /** An inner stream of a `flatMapMerge` has started, so the reader waits for its end as well. */
    data object Started : Signal

    /** The outer stream of a `flatMapMerge` has ended: no inner stream starts after this. */
    data object OuterDone : Signal

    class Threw(val thrown: Throwable) : Signal
}

/** [up] drained into [into] on the thread this runs on, and its end or failure after its last element. */
// The catch is as wide as a pipeline: whatever an input threw is the reader's to throw.
@Suppress("TooGenericExceptionCaught")
private fun feed(up: Pull, into: ArrayBlockingQueue<Any>, releases: Releases?) {
    try {
        Releases.within(releases) { generateSequence { up.next() }.forEach(into::put) }
        into.put(Signal.Finished)
    } catch (_: InterruptedException) {
        // Let go of by the run: nothing reads what would have come next.
    } catch (thrown: Throwable) {
        runCatching { into.put(Signal.Threw(thrown)) }
    }
}

/** Reads what the inputs put, in the order they put it, until [ended] says every input is done. */
private class Confluence(private val queue: ArrayBlockingQueue<Any>) {
    var open = 0
    var outerDone = true

    fun next(): Any? {
        while (open > 0 || !outerDone) {
            when (val taken = queue.take()) {
                Signal.Finished -> open--
                Signal.Started -> open++
                Signal.OuterDone -> outerDone = true
                is Signal.Threw -> throw taken.thrown
                else -> return taken
            }
        }
        return null
    }
}

/**
 * Both streams at once, each drained on a fork of its own into one bounded queue, and read in the order
 * their elements arrived. It ends once both have, and fails as soon as either does.
 */
internal fun Node.Merge.merged(releases: Releases?): Pull {
    val queue = ArrayBlockingQueue<Any>(ROOM)
    val on = releases?.on ?: VirtualThreads
    val inputs = listOf(upstream.pull(), other.pull())
    inputs.forEach { up -> releases?.add(InFlight(on) { feed(up, queue, releases) }::cancel) }
    val reading = Confluence(queue).apply { open = inputs.size }
    return Pull { reading.next() }
}

/**
 * Up to [breadth] inner streams at once, each drained on a fork of its own into one bounded queue. The
 * outer stream is pulled on a fork too, which starts an inner stream only while fewer than [breadth] are
 * running. The run lets go of the outer fork before the inner ones, so no inner stream starts after.
 */
// The catch is as wide as a pipeline: whatever the outer stream or a build threw is the reader's.
@Suppress("TooGenericExceptionCaught")
internal fun Node.FlatMap.merged(breadth: Int, releases: Releases?): Pull {
    val build = guarded("flatMapMerge", at, f)
    val up = upstream.pull()
    val queue = ArrayBlockingQueue<Any>(ROOM)
    val on = releases?.on ?: VirtualThreads
    val room = Semaphore(breadth)
    val inners = ConcurrentLinkedQueue<InFlight<Unit>>()
    val outer = InFlight(on) {
        try {
            Releases.within(releases) {
                generateSequence { up.next() }.forEach { a ->
                    room.acquire()
                    val inner = build(a).node.optimised().pull()
                    queue.put(Signal.Started)
                    inners += InFlight(on) {
                        feed(inner, queue, releases)
                        room.release()
                    }
                }
            }
            queue.put(Signal.OuterDone)
        } catch (_: InterruptedException) {
            // Let go of by the run.
        } catch (thrown: Throwable) {
            runCatching { queue.put(Signal.Threw(thrown)) }
        }
    }
    releases?.add {
        outer.cancel()
        generateSequence { inners.poll() }.forEach(InFlight<Unit>::cancel)
    }
    val reading = Confluence(queue).apply { outerDone = false }
    return Pull { reading.next() }
}

/** [Node.Merge] on a test's clock: each input a worker taking turns, so the order is the same every run. */
// The catch is as wide as a pipeline, as above.
@Suppress("TooGenericExceptionCaught")
internal fun Node.Merge.mergedOnClock(turns: Turns): Pull {
    val held = ArrayDeque<Any>()
    val inputs = listOf(upstream.pull(), other.pull())
    // Of the workers that can go on, the one forked last goes first: forked in reverse, the stream
    // `merge` was called on comes first, then the one given to it.
    inputs.asReversed().forEach { up -> turns.fork { held.drain(up, turns) } }
    return turns.reading(held, open = inputs.size, outerDone = true)
}

/** [Node.FlatMap] merged on a test's clock: the outer stream and each inner one are workers taking turns. */
// The catch is as wide as a pipeline, as above.
@Suppress("TooGenericExceptionCaught")
internal fun Node.FlatMap.mergedOnClock(breadth: Int, turns: Turns): Pull {
    val build = guarded("flatMapMerge", at, f)
    val up = upstream.pull()
    val held = ArrayDeque<Any>()
    var running = 0
    turns.fork {
        try {
            var more = true
            while (more && !turns.stopped) {
                val a = up.next()
                more = a != null
                if (a != null) turns.park { running < breadth }
                if (a != null && !turns.stopped) {
                    val inner = build(a).node.optimised().pull()
                    running++
                    held.addLast(Signal.Started)
                    turns.fork {
                        held.drain(inner, turns)
                        running--
                    }
                }
            }
            held.addLast(Signal.OuterDone)
        } catch (thrown: Throwable) {
            held.addLast(Signal.Threw(thrown))
        }
    }
    return turns.reading(held, open = 0, outerDone = false)
}

/** [up] into this deque in a worker's turns, parking while it is full, and its end or failure after. */
// The catch is as wide as a pipeline, as above.
@Suppress("TooGenericExceptionCaught")
private fun ArrayDeque<Any>.drain(up: Pull, turns: Turns) {
    try {
        while (!turns.stopped) {
            addLast(up.next() ?: break)
            turns.park { size < ROOM }
        }
        addLast(Signal.Finished)
    } catch (thrown: Throwable) {
        addLast(Signal.Threw(thrown))
    }
}

/** The reader on a test's clock: parks while nothing is held, and reads signals as [Confluence] does. */
private fun Turns.reading(held: ArrayDeque<Any>, open: Int, outerDone: Boolean): Pull {
    var inputs = open
    var outer = outerDone
    val anyOpen = { inputs > 0 || !outer }
    return Pull {
        var element: Any? = null
        while (element == null && anyOpen() && !stopped) {
            while (held.isEmpty() && !stopped) park { held.isNotEmpty() }
            when (val taken = held.removeFirstOrNull()) {
                null -> Unit
                Signal.Finished -> inputs--
                Signal.Started -> inputs++
                Signal.OuterDone -> outer = true
                is Signal.Threw -> throw taken.thrown
                else -> element = taken
            }
        }
        element
    }
}
