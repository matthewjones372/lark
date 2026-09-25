package io.github.matthewjones372.lark.stream

import java.util.concurrent.atomic.AtomicBoolean

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

    /** The next element, or `null` once the resource is done or the run was woken to stop. */
    // Whatever a woken next throws is how it was told to stop, so it ends the stream rather than the run.
    @Suppress("TooGenericExceptionCaught")
    fun next(): Any? =
        if (woken.get()) {
            null
        } else {
            try {
                node.next(resource)
            } catch (thrown: Throwable) {
                if (woken.get()) null else throw thrown
            }
        }

    fun wake() {
        if (woken.compareAndSet(false, true)) node.wake(resource)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) node.close(resource)
    }
}
