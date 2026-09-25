@file:OptIn(SourceSeam::class)

package io.github.matthewjones372.lark.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Attributes
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.atomic.AtomicReference

/** For a module that builds a source lark-stream has no builder for, such as a Kafka consumer; not for a pipeline. */
@RequiresOptIn("A seam for a module that extends lark-stream with a source of its own.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SourceSeam

/**
 * What a source may ask of the run it is materialised in: to drain rather than be cut off when the run is
 * stopped, and to hear when the run has ended.
 */
@SourceSeam
class RunHooks internal constructor() {

    private class Stops(val drains: List<() -> Unit>, val stopped: Boolean)

    private val stops = AtomicReference(Stops(emptyList(), stopped = false))
    private val ends = AtomicReference(emptyList<() -> Unit>())

    /** [drain] in place of the kill switch when the run is stopped, and at once if it already was. */
    fun onStop(drain: () -> Unit) {
        val now = stops.updateAndGet { if (it.stopped) it else Stops(it.drains + drain, stopped = false) }
        if (now.stopped) drain()
    }

    /** [action] once the run's exit has completed, however it ended. */
    fun onEnd(action: () -> Unit) {
        ends.updateAndGet { it + action }
    }

    /** Every drain registered so far, run; false when there were none and the kill switch has to do it. */
    internal fun stop(): Boolean {
        val before = stops.getAndUpdate { Stops(it.drains, stopped = true) }
        if (before.stopped) return true
        before.drains.forEach { it() }
        return before.drains.isNotEmpty()
    }

    internal fun ended() = ends.getAndSet(emptyList()).forEach { it() }
}

internal class RunHooksAttribute(val hooks: RunHooks) : Attributes.Attribute

/** A source that is handed the hooks of each run it is materialised in, restarts included. */
@SourceSeam
fun <A : Any> Stream.Companion.hooked(source: (RunHooks) -> Source<A, *>): Stream<Nothing, A> =
    Stream(
        Node.Native(
            Source.fromMaterializer { _, attributes ->
                // A source run outside a Pekko run of lark's, through toSource(), has no hooks, and nothing will
                // call these.
                val hooks = attributes.getAttribute(RunHooksAttribute::class.java)
                    .map { it.hooks }
                    .orElseGet(::RunHooks)
                source(hooks).mapMaterializedValue { NotUsed.notUsed() }
            }.mapMaterializedValue { NotUsed.notUsed() },
            Pekko,
            "Stream.hooked",
            pekkoSite(),
        ),
    )
