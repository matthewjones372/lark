package io.github.matthewjones372.lark.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletionStage
import kotlin.time.toJavaDuration

/**
 * The Pekko stages a node describes: the same ones the operators used to build as they were called.
 *
 * The casts are unchecked because a node is untyped; [Stream] and [Run] hold the types, and what they
 * read back out of a compiled source is what they put in.
 */
@Suppress("UNCHECKED_CAST")
internal fun Node.toPekko(): Source<Any, NotUsed> =
    when (this) {
        is Node.Native -> source as Source<Any, NotUsed>

        is Node.Elements -> Source.from(elements)

        is Node.Single -> Source.single(element)

        is Node.Tick ->
            Source.tick(after.toJavaDuration(), every.toJavaDuration(), element)
                .mapMaterializedValue { NotUsed.getInstance() }

        is Node.FromStage -> (stage as CompletionStage<Any>).asSource(onNull)

        is Node.Fail -> Source.failed(DeclaredFailure(error))

        Node.Empty -> Source.empty()

        Node.Hole -> error("a pipe's input compiled as though it were a source")

        is Node.Unary -> upstream.toPekko().via(stage())
    }

/** A pipe's tree as the Pekko flow it describes, its [Node.Hole] the flow's input. */
internal fun Node.toPekkoFlow(): Flow<Any, Any, NotUsed> =
    when (this) {
        Node.Hole -> Flow.create()

        // Straight off the hole, the stage is the flow: `Pipe.from(flow).toFlow()` is that same flow.
        is Node.Unary -> if (upstream == Node.Hole) stage() else upstream.toPekkoFlow().via(stage())

        is Node.Native, is Node.Elements, is Node.Single, is Node.Tick, is Node.FromStage, is Node.Fail, Node.Empty ->
            error("a source compiled as though it were a pipe: $this")
    }

/** The one Pekko stage a single-input operator is, the same whether it sits on a source or a pipe. */
@Suppress("UNCHECKED_CAST")
private fun Node.Unary.stage(): Flow<Any, Any, NotUsed> =
    when (this) {
        is Node.Stage -> flow as Flow<Any, Any, NotUsed>

        is Node.Map -> {
            val body = guarded("map", at, f)
            Flow.create<Any>().map { a -> body(a) }
        }

        is Node.MapOrFail -> {
            val scope = Failing<Any?>()
            val body = guarded("mapOrFail", at) { a: Any -> scope.f(a) }
            Flow.create<Any>().map { a -> body(a) }
        }

        is Node.Filter -> {
            val test = guarded("filter", at, predicate)
            Flow.create<Any>().filter { a -> test(a) }
        }
    }

@Suppress("UNCHECKED_CAST")
internal fun End.toPekko(): Sink<Any, CompletionStage<Any>> =
    when (this) {
        End.Collect -> Sink.seq<Any>() as Sink<Any, CompletionStage<Any>>
        is End.Fold -> Sink.fold(zero) { total, a -> f(total, a) }
        is End.Native -> sink as Sink<Any, CompletionStage<Any>>
    }
