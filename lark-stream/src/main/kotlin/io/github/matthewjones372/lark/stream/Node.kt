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

    /** A pipe's input: the place a stream goes when the pipe is spliced onto it. */
    data object Hole : Node

    /** One input, one output: every operator a [Pipe] can hold, and the ones [spliced] rebuilds. */
    sealed interface Unary : Node {
        val upstream: Node

        /** This operator, reading from [upstream] instead. */
        fun on(upstream: Node): Unary
    }

    /** A Pekko flow: one a caller handed over, or one an operator not yet described here built. */
    data class Stage(override val upstream: Node, val flow: Flow<*, *, NotUsed>) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** [at] is read where the operator was written, which is the only time the caller's frame is there to read. */
    data class Map(override val upstream: Node, val f: (Any) -> Any, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapOrFail(override val upstream: Node, val f: Failing<Any?>.(Any) -> Any, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Filter(override val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }
}

/**
 * A pipe's tree with its [Node.Hole] replaced by [onto]: splicing rebuilds the pipe's own nodes and
 * shares everything upstream, so a pipe reused across streams is copied once per splice.
 */
internal fun Node.spliced(onto: Node): Node =
    when (this) {
        Node.Hole -> onto

        is Node.Unary -> on(upstream.spliced(onto))

        is Node.Native, is Node.Elements, is Node.Single, is Node.Tick, is Node.FromStage, is Node.Fail, Node.Empty ->
            error("a pipe holds single-input operators only, and reached $this")
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
