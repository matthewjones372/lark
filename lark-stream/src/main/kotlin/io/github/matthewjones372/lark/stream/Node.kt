package io.github.matthewjones372.lark.stream

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.ScheduleStep
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import kotlin.time.Duration

/**
 * A stream as data: what the operators build and what a backend compiles.
 *
 * Untyped inside, because the element and failure types are [Stream]'s to carry and a node is only
 * ever reached through the stream that typed it.
 */
internal sealed interface Node {

    /** A Pekko source a caller handed over. Only a Pekko backend can run one. */
    class Native(val source: Source<*, NotUsed>) : StreamOnly

    class Elements(val elements: Iterable<Any>) : StreamOnly

    class Single(val element: Any) : StreamOnly

    class Tick(val every: Duration, val element: Any, val after: Duration) : StreamOnly

    class FromStage(val stage: CompletionStage<*>, val onNull: () -> Throwable) : StreamOnly

    class Fail(val error: Any?) : StreamOnly

    data object Empty : StreamOnly

    /** A node no pipe can hold: a source, or an operator that reads more than the one stream it is on. */
    sealed interface StreamOnly : Node

    /** Pekko's `merge`: both, in whatever order they arrive. */
    class Merge(val upstream: Node, val other: Node) : StreamOnly

    class Interleave(val upstream: Node, val other: Node, val segmentSize: Int) : StreamOnly

    class ZipWith(val upstream: Node, val other: Node, val f: (Any, Any) -> Any, val at: String) : StreamOnly

    /** [first]'s elements, then [upstream]'s: Pekko's `prepend`, which is not quite `first.concat`. */
    class Prepend(val upstream: Node, val first: Node) : StreamOnly

    class Concat(val upstream: Node, val next: Node) : StreamOnly

    /** [upstream] materialised again on a defect, as [step] decides: the whole stream, never one stage. */
    class RestartOnDefect(val upstream: Node, val step: ScheduleStep<Throwable, *>, val restarts: Restarts) : StreamOnly

    /** A pipe's input: the place a stream goes when the pipe is spliced onto it. */
    data object Hole : Node

    /** One input, one output: every operator a [Pipe] can hold, and the ones [spliced] rebuilds. */
    sealed interface Unary : Node {
        val upstream: Node

        /** This operator, reading from [upstream] instead. */
        fun on(upstream: Node): Unary
    }

    /** A Pekko flow a caller handed over. Only a Pekko backend can run one. */
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
    data class FilterNot(override val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Take(override val upstream: Node, val n: Long) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Drop(override val upstream: Node, val n: Long) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class TakeWhile(override val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class DropWhile(override val upstream: Node, val predicate: (Any) -> Boolean, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Grouped(override val upstream: Node, val n: Int) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Sliding(override val upstream: Node, val n: Int, val step: Int) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class GroupedWithin(override val upstream: Node, val n: Int, val within: Duration) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Buffer(override val upstream: Node, val size: Int, val strategy: OverflowStrategy) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Scan(override val upstream: Node, val zero: Any, val f: (Any, Any) -> Any, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapConcat(override val upstream: Node, val f: (Any) -> Iterable<Any>, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapAsync(
        override val upstream: Node,
        val parallelism: Int,
        val f: (Any) -> CompletionStage<Any>,
        val at: String,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Conflate(
        override val upstream: Node,
        val seed: (Any) -> Any,
        val aggregate: (Any, Any) -> Any,
        val at: String,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** The declared failure as a last element, `Left`; every element before it a `Right`. */
    data class Either(override val upstream: Node) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class Absolve(override val upstream: Node, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class DivertLefts(override val upstream: Node, val to: Sink<*, *>, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** [f] answers with the node of the stream that takes over, so the recovery is described too. */
    data class CatchAll(override val upstream: Node, val f: (Any?) -> Node) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapError(override val upstream: Node, val f: (Any?) -> Any?, val at: String) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class OrFailIfEmpty(override val upstream: Node, val error: Any?) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** [dropping] is `wireTap`: the tap is dropped from rather than allowed to slow the pipeline. */
    data class Tap(override val upstream: Node, val to: Sink<*, *>, val dropping: Boolean) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /**
     * [f] answers with a [Stream] rather than its node, so the compiler can reuse the source a stream
     * built once already has: `flatten` over streams described ahead of time compiles each of them once.
     */
    data class FlatMap(
        override val upstream: Node,
        val f: (Any) -> Stream<*, Any>,
        val breadth: Int?,
        val at: String,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    data class MapPar(
        override val upstream: Node,
        val parallelism: Int,
        val on: Executor,
        val f: Raise<Any?>.(Any) -> Any,
        val at: String,
    ) : Unary {
        override fun on(upstream: Node) = copy(upstream = upstream)
    }

    /** `S` may be nullable, which is why the state is `Any?` where every element is `Any`. */
    data class StatefulMap(
        override val upstream: Node,
        val create: () -> Any?,
        val f: (Any?, Any) -> Pair<Any?, Any>,
        val onComplete: (Any?) -> Any?,
        val at: String,
    ) : Unary {
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
        is Node.StreamOnly -> error("a pipe holds single-input operators only, and reached $this")
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
