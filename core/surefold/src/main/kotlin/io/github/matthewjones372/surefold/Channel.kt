package io.github.matthewjones372.surefold

import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded hand-off between forks of one scope (spec 0038): a producer [send]s, a consumer [receive]s or iterates,
 * and at most [capacity] values wait between them, so a producer ahead of its consumer parks rather than running away.
 * A capacity of zero hands each value straight from a sender to a receiver.
 *
 * The producer [close]s it at the end of its data, and a consumer drains what was sent before that. The consumer
 * [cancel]s it to stop early: a send parked or still to come answers `false`, so a producer loop ends without an
 * interrupt. The scope that opened it cancels it when it closes.
 */
class Channel<A : Any> internal constructor(val capacity: Int) : Iterable<A> {

    init {
        require(capacity >= 0) { "a channel holds no fewer than zero values, and this one was asked for $capacity" }
    }

    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<A>()
    private val senders = ArrayDeque<Parked>()
    private val receivers = ArrayDeque<Parked>()
    private var closed = false
    private var cancelled = false

    /**
     * [value] handed on, parking while the channel is full: true once it is in the channel or a receiver's hands,
     * false if the consumer cancelled. Sending on a closed channel throws [IllegalStateException].
     */
    fun send(value: A): Boolean {
        val parked = lock.withLock {
            if (cancelled) return false
            check(!closed) { "a value was sent on a closed channel" }
            receivers.removeFirstOrNull()?.let { receiver ->
                receiver.answer(value)
                return true
            }
            if (buffer.size < capacity) {
                buffer.addLast(value)
                return true
            }
            Parked(value).also(senders::addLast)
        }
        return when (val answer = parked.await(senders)) {
            Taken -> true
            Stopped -> false
            else -> error("a sender is answered taken or stopped, not $answer")
        }
    }

    /** The next value, parking while the channel is empty: null once it is closed and drained, or cancelled. */
    fun receive(): A? {
        val parked = lock.withLock {
            buffer.removeFirstOrNull()?.let { value ->
                // Room was made: the first sender waiting for it puts its value in.
                senders.removeFirstOrNull()?.let { sender ->
                    buffer.addLast(checkNotNull(sender.value) { "a parked sender brings a value" })
                    sender.answer(Taken)
                }
                return value
            }
            senders.removeFirstOrNull()?.let { sender ->
                sender.answer(Taken)
                return sender.value
            }
            if (closed || cancelled) return null
            Parked(null).also(receivers::addLast)
        }
        @Suppress("UNCHECKED_CAST")
        return when (val answer = parked.await(receivers)) {
            Stopped -> null
            else -> answer as A
        }
    }

    /** No more values: a later [send] throws, and a receiver gets what was already sent, then null. */
    fun close() = lock.withLock {
        closed = true
        // A receiver parks only on an empty channel with no sender waiting: nothing more will reach it.
        generateSequence { receivers.removeFirstOrNull() }.forEach { it.answer(Stopped) }
    }

    /** The consumer has stopped: what waits is dropped, and every parked or later send answers false. */
    fun cancel() = lock.withLock {
        cancelled = true
        buffer.clear()
        generateSequence { senders.removeFirstOrNull() }.forEach { it.answer(Stopped) }
        generateSequence { receivers.removeFirstOrNull() }.forEach { it.answer(Stopped) }
    }

    /** How many values wait in the channel now, for a test that says it never holds more than [capacity]. */
    internal fun buffered(): Int = lock.withLock { buffer.size }

    /** Every value received until the channel is closed and drained, or cancelled. */
    override fun iterator(): Iterator<A> = object : Iterator<A> {
        private var next: A? = null
        private var ended = false

        override fun hasNext(): Boolean {
            if (next == null && !ended) next = receive().also { if (it == null) ended = true }
            return next != null
        }

        override fun next(): A {
            hasNext()
            val value = next ?: throw NoSuchElementException("the channel is closed and drained")
            next = null
            return value
        }
    }

    /**
     * A thread parked on the channel, with the value it brings as a sender. It is answered with a value, [Taken] or
     * [Stopped] under the channel's lock, and unparked.
     */
    private inner class Parked(val value: A?) {
        private val thread = Thread.currentThread()

        @Volatile
        private var answer: Any? = Unanswered

        fun answer(with: Any) {
            answer = with
            LockSupport.unpark(thread)
        }

        /**
         * Parks until answered. Interrupted first, it leaves [queue] and throws, so a sender's value is never taken
         * by a receiver after the sender gave up on it; answered first, the interrupt is kept for the caller.
         */
        fun await(queue: ArrayDeque<Parked>): Any {
            while (answer === Unanswered) {
                LockSupport.park(this@Channel)
                if (Thread.interrupted()) {
                    val left = lock.withLock { answer === Unanswered && queue.remove(this) }
                    if (left) throw InterruptedException("interrupted while parked on a channel")
                    Thread.currentThread().interrupt()
                }
            }
            return checkNotNull(answer) { "a parked thread is answered with something" }
        }
    }
}

/** A channel of [capacity] between this scope's forks, cancelled when the scope closes (spec 0038). */
fun <A : Any> Flock<*>.channel(capacity: Int): Channel<A> = Channel<A>(capacity).also { channel ->
    onClose { channel.cancel() }
}

private object Unanswered

private object Taken

private object Stopped
