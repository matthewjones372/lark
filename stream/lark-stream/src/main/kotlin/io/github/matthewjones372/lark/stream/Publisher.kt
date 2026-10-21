package io.github.matthewjones372.lark.stream

import java.util.concurrent.Flow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * What [publisher] sends, read with bounded demand, on any backend. A run subscribes when it starts, asks for
 * [prefetch] elements, and asks for half as many again each time half have been taken, so at most [prefetch]
 * are ever owed. A run that stops cancels its subscription. A publisher that fails ends the run with what it
 * failed with, as a failed `fromStage` does; one that sends more than it was asked for is a broken publisher,
 * and ends the run the same way.
 */
fun <A : Any> Stream.Companion.fromPublisher(
    publisher: Flow.Publisher<A>,
    prefetch: Int = DEFAULT_PREFETCH,
): Stream<Nothing, A> {
    require(prefetch > 0) { "a publisher is asked for at least one element at a time, not $prefetch" }
    return Stream.blocking(
        open = { Subscribed<A>(prefetch).also(publisher::subscribe) },
        next = { it.take() },
        wake = { it.end() },
        close = { it.cancel() },
    )
}

/** As many as Pekko's default input buffer holds, and Forks' merge. */
private const val DEFAULT_PREFETCH = 16

/** One run's subscription: what the publisher sent, then how it ended, in a queue the run takes from. */
private class Subscribed<A : Any>(private val prefetch: Int) : Flow.Subscriber<A> {

    private val queue = LinkedBlockingQueue<Any>()
    private val subscription = AtomicReference<Flow.Subscription?>()
    private val cancelled = AtomicBoolean(false)

    /** Asked for and not yet sent. Below zero, the publisher sent what nobody asked for. */
    private val owed = AtomicLong()

    /** How many are asked for again each time that many have been taken. */
    private val batch = maxOf(1, prefetch / 2)
    private val taken = AtomicInteger()

    /** How the stream ended, once the run has read it: an element sent after a cancel is never read. */
    private val ended = AtomicReference<Ended?>()

    /** How the run that opened this waits, when its backend must know: see [Waiting]. */
    private val waiting: Waiting? = Waiting.here()

    override fun onSubscribe(subscription: Flow.Subscription) {
        // A second subscription, or one that arrives after the run let go, is cancelled at once (§2.5).
        if (cancelled.get() || !this.subscription.compareAndSet(null, subscription)) {
            subscription.cancel()
            return
        }
        owed.addAndGet(prefetch.toLong())
        subscription.request(prefetch.toLong())
        // A run that let go between the check and the set would leave this subscription running.
        if (cancelled.get()) subscription.cancel()
    }

    override fun onNext(item: A) {
        if (owed.decrementAndGet() < 0) {
            cancel()
            signal(Ended.Threw(IllegalStateException("a publisher sent more than the $prefetch it was asked for")))
        } else {
            signal(item)
        }
    }

    override fun onError(throwable: Throwable) = signal(Ended.Threw(throwable))

    override fun onComplete() = signal(Ended.Done)

    /** The next element, waiting for one; `null` once the publisher has completed or the run let go. */
    @Suppress("UNCHECKED_CAST")
    fun take(): A? {
        ended.get()?.let { return it.rethrow() }
        val next = if (waiting == null) {
            queue.take()
        } else {
            waiting.until { queue.isNotEmpty() }
            queue.poll() ?: return null
        }
        return if (next is Ended) {
            ended.set(next)
            next.rethrow()
        } else {
            (next as A).also { askAgain() }
        }
    }

    /** Wakes a run waiting in [take], as its stop does, and lets the publisher go. */
    fun end() {
        cancel()
        signal(Ended.Done)
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) subscription.get()?.cancel()
    }

    private fun askAgain() {
        if (taken.incrementAndGet() < batch) return
        taken.set(0)
        owed.addAndGet(batch.toLong())
        subscription.get()?.request(batch.toLong())
    }

    private fun signal(signal: Any) {
        queue.put(signal)
        waiting?.changed()
    }

    /** How the publisher ended, or how the run let go of it. */
    private sealed interface Ended {
        /** `null` for an end, and throws for a failure. */
        fun rethrow(): Nothing? = (this as? Threw)?.let { throw it.thrown }

        data object Done : Ended

        class Threw(val thrown: Throwable) : Ended
    }
}
