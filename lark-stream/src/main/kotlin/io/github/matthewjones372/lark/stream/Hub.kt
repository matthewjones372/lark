package io.github.matthewjones372.lark.stream

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Why a [Hub] turned an element away. The element was given to no subscriber, so it can be offered again. */
sealed interface HubRefused {

    /** A subscriber's queue has no room: it is reading more slowly than the hub is published to. */
    data object Full : HubRefused

    /** The hub has closed and takes nothing more. */
    data object Closed : HubRefused
}

/**
 * One publisher's elements, read by every subscriber in the same process, on any backend.
 *
 * Each subscriber has a queue of its own, holding at most [capacity]. [publish] never waits: an element
 * goes to every subscriber or, when one of them is full, to none, and the caller is told which. What is
 * published before anyone has subscribed is held, up to [capacity], for the first subscriber; once there
 * has been one, an element published while there is none is accepted and goes nowhere, as a broadcast to
 * nobody does.
 *
 * A subscriber is a run of [subscribe], from the moment it starts until it ends however it ends, so a
 * subscriber that stops no longer holds the hub back.
 */
class Hub<A : Any>(val capacity: Int = DEFAULT_CAPACITY) : AutoCloseable {

    init {
        require(capacity > 0) { "a hub holds at least one element for each subscriber, not $capacity" }
    }

    private val lock = ReentrantLock()
    private val early = ArrayDeque<A>()
    private val queues = LinkedHashSet<Subscriber>()
    private var subscribedOnce = false
    private var closed = false

    /** How many subscriptions are running now. */
    val subscribers: Int get() = lock.withLock { queues.size }

    /** [element] to every subscriber, or to none of them with the reason. Never waits for room. */
    fun publish(element: A): Either<HubRefused, A> = lock.withLock {
        when {
            closed -> HubRefused.Closed.left()
            !subscribedOnce && early.size >= capacity -> HubRefused.Full.left()
            !subscribedOnce -> element.right().also { early.addLast(element) }
            queues.any { it.size >= capacity } -> HubRefused.Full.left()
            else -> element.right().also { queues.forEach { queue -> queue.put(element) } }
        }
    }.also { if (it.isRight()) changed() }

    /**
     * Everything published from the moment a run of this starts, in order; the first run ever also gets
     * what was held for it. A description, so each run is a subscriber of its own. It ends `Done` once the
     * hub has closed and it has read what it was sent.
     */
    fun subscribe(): Stream<Nothing, A> =
        Stream.blocking(open = { register() }, next = { it.take() }, wake = { it.end() }, close = { unregister(it) })

    /** Takes nothing more, and ends every subscription once it has read what it holds. */
    override fun close() = lock.withLock {
        closed = true
        queues.forEach { it.end() }
    }

    /** Asks a subscriber that waits through its backend to look again; outside the lock, which it takes. */
    private fun changed() = lock.withLock { queues.mapNotNull { it.waiting } }.forEach { it.changed() }

    private fun register(): Subscriber = lock.withLock {
        Subscriber().also { queue ->
            if (!subscribedOnce) {
                early.forEach(queue::put)
                early.clear()
                subscribedOnce = true
            }
            if (closed) queue.end()
            queues += queue
        }
    }

    private fun unregister(queue: Subscriber) = lock.withLock { queues -= queue }

    /** One subscriber's queue: the elements sent to it, then [End] once the hub closes or the run stops. */
    private inner class Subscriber {
        private val queue = LinkedBlockingQueue<Any>()

        /** How the run that opened this waits, when its backend must know: see [Waiting]. */
        val waiting: Waiting? = Waiting.here()

        val size: Int get() = queue.size

        fun put(element: A) = queue.put(element)

        fun end() {
            queue.put(End)
            waiting?.changed()
        }

        /** The next element, waiting for one; `null` once the subscription has ended or its run stopped. */
        @Suppress("UNCHECKED_CAST")
        fun take(): A? {
            val next = if (waiting == null) {
                queue.take()
            } else {
                waiting.until { queue.isNotEmpty() }
                queue.poll() ?: return null
            }
            return if (next === End) null.also { queue.put(End) } else next as A
        }
    }

    /** What ends a subscriber's queue. Put back when read, so a read after the end ends again. */
    private object End

    companion object {
        /** A queue for each subscriber deep enough for a burst, small enough that a stalled one shows. */
        const val DEFAULT_CAPACITY: Int = 256
    }
}

/**
 * Publishes each element to [hub] in turn, as it passes. An element the hub refuses ends the stream with
 * the refusal in its failure type, where the publisher decides whether to wait and try again.
 */
fun <A : Any> Stream<Nothing, A>.publishTo(hub: Hub<A>): Stream<HubRefused, A> =
    mapOrFail { element -> hub.publish(element).bind() }
