package io.github.matthewjones372.lark.stream

import arrow.core.Either
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.Optional
import java.util.concurrent.CompletionStage
import kotlin.time.toJavaDuration
import org.apache.pekko.japi.Pair as PekkoPair

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

        is Node.Merge -> upstream.toPekko().merge(other.toPekko())

        is Node.Interleave -> upstream.toPekko().interleave(other.toPekko(), segmentSize)

        is Node.ZipWith -> zipped()

        is Node.Prepend -> upstream.toPekko().prepend(first.toPekko())

        is Node.Concat -> upstream.toPekko().concat(next.toPekko())

        is Node.RestartOnDefect -> restarting(upstream.toPekko(), step, restarts)

        Node.Hole -> error("a pipe's input compiled as though it were a source")

        is Node.Unary -> upstream.toPekko().via(stage())
    }

/** A pipe's tree as the Pekko flow it describes, its [Node.Hole] the flow's input. */
internal fun Node.toPekkoFlow(): Flow<Any, Any, NotUsed> =
    when (this) {
        Node.Hole -> Flow.create()

        // Straight off the hole, the stage is the flow: `Pipe.from(flow).toFlow()` is that same flow.
        is Node.Unary -> if (upstream == Node.Hole) stage() else upstream.toPekkoFlow().via(stage())

        is Node.StreamOnly -> error("a stream compiled as though it were a pipe: $this")
    }

/** The one Pekko stage a single-input operator is, the same whether it sits on a source or a pipe. */
@Suppress("UNCHECKED_CAST")
private fun Node.Unary.stage(): Flow<Any, Any, NotUsed> =
    when (this) {
        is Node.Stage -> flow as Flow<Any, Any, NotUsed>

        is Node.Map -> mapStage()

        is Node.MapOrFail -> mapOrFailStage()

        is Node.Filter -> filterStage()

        is Node.FilterNot -> filterNotStage()

        is Node.Take -> Flow.create<Any>().take(n)

        is Node.Drop -> Flow.create<Any>().drop(n)

        is Node.TakeWhile -> takeWhileStage()

        is Node.DropWhile -> dropWhileStage()

        is Node.Grouped -> Flow.create<Any>().grouped(n).map { batch -> batch.toList() }

        is Node.Sliding -> Flow.create<Any>().sliding(n, step).map { window -> window.toList() }

        is Node.GroupedWithin ->
            Flow.create<Any>().groupedWithin(n, within.toJavaDuration()).map { batch -> batch.toList() }

        is Node.Buffer -> Flow.create<Any>().buffer(size, strategy)

        is Node.Scan -> scanStage()

        is Node.StatefulMap -> statefulMapStage()

        is Node.MapConcat -> mapConcatStage()

        is Node.MapAsync -> mapAsyncStage()

        is Node.Conflate -> conflateStage()

        is Node.Either -> eitherStage()

        is Node.Absolve -> absolveStage()

        is Node.DivertLefts -> divertLeftsStage()

        is Node.CatchAll ->
            Flow.create<Any>().recoverWithRetries(1, onDeclaredFailure { e: Any? -> f(e).toPekko() })

        is Node.MapError -> mapErrorStage()

        is Node.OrFailIfEmpty -> orFailIfEmptyStage()

        is Node.Tap -> tapStage()

        is Node.FlatMap -> flatMapStage()

        is Node.MapPar -> mapParStage()
    } as Flow<Any, Any, NotUsed>

private fun Node.Map.mapStage(): Flow<Any, *, NotUsed> {
    val body = guarded("map", at, f)
    return Flow.create<Any>().map { a -> body(a) }
}

private fun Node.MapOrFail.mapOrFailStage(): Flow<Any, *, NotUsed> {
    val scope = Failing<Any?>()
    val body = guarded("mapOrFail", at) { a: Any -> scope.f(a) }
    return Flow.create<Any>().map { a -> body(a) }
}

private fun Node.Filter.filterStage(): Flow<Any, *, NotUsed> {
    val test = guarded("filter", at, predicate)
    return Flow.create<Any>().filter { a -> test(a) }
}

private fun Node.FilterNot.filterNotStage(): Flow<Any, *, NotUsed> {
    val test = guarded("filterNot", at, predicate)
    return Flow.create<Any>().filterNot { a -> test(a) }
}

private fun Node.TakeWhile.takeWhileStage(): Flow<Any, *, NotUsed> {
    val test = guarded("takeWhile", at, predicate)
    return Flow.create<Any>().takeWhile { a -> test(a) }
}

private fun Node.DropWhile.dropWhileStage(): Flow<Any, *, NotUsed> {
    val test = guarded("dropWhile", at, predicate)
    return Flow.create<Any>().dropWhile { a -> test(a) }
}

private fun Node.Scan.scanStage(): Flow<Any, *, NotUsed> {
    val carry = guarded("scan", at, f)
    return Flow.create<Any>().scan(zero) { carried, a -> carry(carried, a) }
}

private fun Node.StatefulMap.statefulMapStage(): Flow<Any, *, NotUsed> {
    val step = guarded("statefulMap", at, f)
    return Flow.create<Any>().statefulMap(
        { create() },
        { carried, a -> step(carried, a).let { (next, out) -> PekkoPair.create(next, out) } },
        { carried -> Optional.ofNullable(onComplete(carried)) },
    )
}

private fun Node.MapConcat.mapConcatStage(): Flow<Any, *, NotUsed> {
    val body = guarded("mapConcat", at, f)
    return Flow.create<Any>().mapConcat { a -> body(a) }
}

private fun Node.MapAsync.mapAsyncStage(): Flow<Any, *, NotUsed> {
    val stage = guarded("mapAsync", at, f)
    return Flow.create<Any>().mapAsync(parallelism) { a -> stage(a).orDieOnNull(a, at) }
}

private fun Node.Conflate.conflateStage(): Flow<Any, *, NotUsed> {
    val start = guarded("conflateWithSeed", at, seed)
    val fold = guarded("conflateWithSeed", at, aggregate)
    return Flow.create<Any>().conflateWithSeed({ a -> start(a) }, { s, a -> fold(s, a) })
}

private fun Node.Either.eitherStage(): Flow<Any, *, NotUsed> {
    val rights: Flow<Any, Either<Any?, Any>, NotUsed> = Flow.create<Any>().map { a -> Either.Right(a) }
    return rights.recoverWithRetries(
        1,
        onDeclaredFailure { e: Any? -> Source.single<Either<Any?, Any>>(Either.Left(e)) },
    )
}

@Suppress("UNCHECKED_CAST")
private fun Node.Absolve.absolveStage(): Flow<Any, *, NotUsed> {
    val decided = guarded("absolve", at) { either: Any ->
        (either as Either<Any?, Any>).fold({ left -> throw DeclaredFailure(left) }, { right -> right })
    }
    return Flow.create<Any>().map { either -> decided(either) }
}

@Suppress("UNCHECKED_CAST")
private fun Node.DivertLefts.divertLeftsStage(): Flow<Any, *, NotUsed> {
    // Flipped, so that both branches read their element through the one fold `decided` is.
    val lefts = Flow.fromFunction<Either<Any, Any>, Any> { either -> either.swap().decided() }.to(to as Sink<Any, *>)
    val right = guarded("divertLefts", at) { either: Either<Any, Any> -> either.decided() }
    return Flow.create<Either<Any, Any>>()
        .divertTo(lefts) { either -> either.isLeft() }
        .map { either -> right(either) } as Flow<Any, *, NotUsed>
}

private fun Node.MapError.mapErrorStage(): Flow<Any, *, NotUsed> {
    val mapped = guardedError("mapError", at, f)
    return Flow.create<Any>().recoverWithRetries(
        1,
        onDeclaredFailure { e: Any? -> Source.failed<Any>(DeclaredFailure(mapped(e))) },
    )
}

// The alternative is deferred because a plain `Source.failed` fails at materialisation, which would
// fail every stream through here rather than the empty ones.
private fun Node.OrFailIfEmpty.orFailIfEmptyStage(): Flow<Any, *, NotUsed> =
    Flow.create<Any>().orElse(Source.lazySource { Source.failed<Any>(DeclaredFailure(error)) })

@Suppress("UNCHECKED_CAST")
private fun Node.Tap.tapStage(): Flow<Any, *, NotUsed> {
    val tap = to as Sink<Any, *>
    return if (dropping) Flow.create<Any>().wireTap(tap) else Flow.create<Any>().alsoTo(tap)
}

private fun Node.ZipWith.zipped(): Source<Any, NotUsed> {
    val combine = guarded("zipWith", at, f)
    return upstream.toPekko().zipWith(other.toPekko()) { a, b -> combine(a, b) }
}

private fun Node.FlatMap.flatMapStage(): Flow<Any, *, NotUsed> {
    val build = guarded(if (breadth == null) "flatMapConcat" else "flatMapMerge", at, f)
    return if (breadth == null) {
        Flow.create<Any>().flatMapConcat { a -> build(a).source }
    } else {
        Flow.create<Any>().flatMapMerge(breadth) { a -> build(a).source }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun End.toPekko(): Sink<Any, CompletionStage<Any>> =
    when (this) {
        End.Collect -> Sink.seq<Any>() as Sink<Any, CompletionStage<Any>>
        is End.Fold -> Sink.fold(zero) { total, a -> f(total, a) }
        is End.Native -> sink as Sink<Any, CompletionStage<Any>>
    }
