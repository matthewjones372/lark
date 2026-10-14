package io.github.matthewjones372.lark.stream

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A resource opened once per run and read by blocking: a consumer's `poll`, a cursor, a queue's `take`.
 *
 * [next] is called by one thread at a time and `null` ends the stream. [wake] is how a run's `stop()`
 * reaches a [next] that is blocked, from any thread; a [next] it interrupts may return `null` or throw,
 * and either ends the stream. [close] runs exactly once, after the last [next], however the run ended.
 */
fun <R : Any, A : Any> Stream.Companion.blocking(
    open: () -> R,
    next: (R) -> A?,
    wake: (R) -> Unit,
    close: (R) -> Unit,
): Stream<Nothing, A> =
    Stream(Node.Blocking(open, next.erased(), wake.erased(), close.erased(), buildSite()))

/**
 * One run's resource, for a backend: the rules [Stream.Companion.blocking] promises, kept in one place so
 * every backend keeps them the same way.
 */
@StreamSpi
class Opened(private val node: Node.Blocking) {

    private val resource: Any = node.open()
    private val woken = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** Held for each read, so a close from another thread waits for the read it would close under. */
    private val reading = ReentrantLock()

    /** The next element, or `null` once the resource is done or the run was woken to stop. */
    // Whatever a woken next throws is how it was told to stop, so it ends the stream rather than the run.
    @Suppress("TooGenericExceptionCaught")
    fun next(): Any? = reading.withLock {
        if (woken.get() || closed.get()) {
            null
        } else {
            try {
                node.next(resource)
            } catch (thrown: Throwable) {
                if (woken.get()) null else throw thrown
            }
        }
    }

    /** Tells a blocked [next] to return; a resource already closed is not asked. */
    fun wake() {
        if (woken.compareAndSet(false, true) && !closed.get()) node.wake(resource)
    }

    /**
     * Closed once, after the last read. A read still running on another thread, a feed's fork given up by a
     * restart, is woken first: a Kafka consumer closed under its own `poll` throws and stays in its group.
     */
    fun close() {
        if (!reading.tryLock()) {
            wake()
            reading.lock()
        }
        try {
            if (closed.compareAndSet(false, true)) node.close(resource)
        } finally {
            reading.unlock()
        }
    }
}
