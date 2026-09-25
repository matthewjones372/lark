package io.github.matthewjones372.lark.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletionStage
import kotlin.time.Duration

/**
 * A stream as data: what the operators build and what a backend compiles.
 *
 * Untyped inside, because the element and failure types are [Stream]'s to carry and a node is only
 * ever reached through the stream that typed it.
 */
internal sealed interface Node {

    /** A Pekko source: one a caller handed over, or one an operator not yet described here built. */
    class Native(val source: Source<*, NotUsed>) : Node

    class Elements(val elements: Iterable<Any>) : Node

    class Single(val element: Any) : Node

    class Tick(val every: Duration, val element: Any, val after: Duration) : Node

    class FromStage(val stage: CompletionStage<*>, val onNull: () -> Throwable) : Node

    class Fail(val error: Any?) : Node

    data object Empty : Node

    /** A pipe spliced on. `Pipe` is still a Pekko flow, until its own operators are nodes. */
    class Via(val upstream: Node, val flow: Flow<*, *, NotUsed>) : Node

    /** [at] is read where the operator was written, which is the only time the caller's frame is there to read. */
    class Map(val upstream: Node, val f: (Any) -> Any, val at: String) : Node

    class MapOrFail(val upstream: Node, val f: Failing<Any?>.(Any) -> Any, val at: String) : Node

    class Filter(val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Node
}

/** Where a run's elements go, and what it completes with. */
internal sealed interface End {

    data object Collect : End

    class Fold(val zero: Any, val f: (Any, Any) -> Any) : End

    class Native(val sink: Sink<*, out CompletionStage<*>>) : End
}

/** A typed caller function as a node holds it. Erasure makes this a no-op; the stream's types say what comes out. */
@Suppress("UNCHECKED_CAST")
internal fun <F> Function<*>.erased(): F = this as F
