package io.github.matthewjones372.lark.stream

import arrow.core.Either
import org.apache.pekko.NotUsed
import org.apache.pekko.japi.pf.PFBuilder
import org.apache.pekko.stream.Graph
import org.apache.pekko.stream.SourceShape
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import scala.PartialFunction
import java.util.concurrent.CompletionStage

/**
 * Up to [parallelism] stages run at once and their results keep the input's order.
 *
 * A stage that completes with `null` dies here. Pekko's own `mapAsync` documents that such an
 * element "is not passed downstream", and a lookup lifted into a future is the easiest thing in
 * Kotlin to write and the hardest to notice.
 */
fun <A : Any, B : Any> Pipe.Companion.mapAsync(parallelism: Int, f: (A) -> CompletionStage<B>): Pipe<Nothing, A, B> =
    Pipe(Node.MapAsync(Node.Hole, parallelism, f.erased(), buildSite()))

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapAsync(
    parallelism: Int,
    f: (Out) -> CompletionStage<Out2>,
): Pipe<E, In, Out2> = via(Pipe.mapAsync(parallelism, f))

/**
 * A backlog collapsed into one element: [seed] starts an aggregate from the element downstream was
 * not ready for, and [aggregate] folds each later one into it until downstream asks again.
 *
 * Both halves run caller code, so both are guarded, and the element a defect names is the one being
 * folded in. Nothing is dropped: what an aggregate leaves out is what the caller left out of it.
 */
fun <A : Any, S : Any> Pipe.Companion.conflateWithSeed(seed: (A) -> S, aggregate: (S, A) -> S): Pipe<Nothing, A, S> =
    Pipe(Node.Conflate(Node.Hole, seed.erased(), aggregate.erased(), buildSite()))

fun <E, In, Out : Any, S : Any> Pipe<E, In, Out>.conflateWithSeed(
    seed: (Out) -> S,
    aggregate: (S, Out) -> S,
): Pipe<E, In, S> = via(Pipe.conflateWithSeed(seed, aggregate))

/** Each element's own elements, in its order; one that answers with none emits none. */
fun <A : Any, B : Any> Pipe.Companion.mapConcat(f: (A) -> Iterable<B>): Pipe<Nothing, A, B> =
    Pipe(Node.MapConcat(Node.Hole, f.erased(), buildSite()))

fun <E, In, Out : Any, B : Any> Pipe<E, In, Out>.mapConcat(f: (Out) -> Iterable<B>): Pipe<E, In, B> =
    via(Pipe.mapConcat(f))

/** The declared failure becomes the last element, as a `Left`, leaving none for the type to carry. */
fun <E, A : Any> Pipe.Companion.either(): Pipe<Nothing, A, Either<E, A>> = Pipe(Node.Either(Node.Hole))

fun <E, In, Out : Any> Pipe<E, In, Out>.either(): Pipe<Nothing, In, Either<E, Out>> = replacing(Pipe.either<E, Out>())

/** A `Left` fails the pipe with what it holds; a `Right` carries on as the element. */
fun <E, L : E, R : Any> Pipe.Companion.absolve(): Pipe<E, Either<L, R>, R> = Pipe(Node.Absolve(Node.Hole, buildSite()))

fun <E, In, L : E, R : Any> Pipe<E, In, Either<L, R>>.absolve(): Pipe<E, In, R> = via(Pipe.absolve<E, L, R>())

/** Every `Left` reaches [to] and every `Right` carries on: `divertTo` with no predicate to write. */
fun <L : Any, R : Any> Pipe.Companion.divertLefts(to: Sink<L, *>): Pipe<Nothing, Either<L, R>, R> {
    // Flipped, so that both branches read their element through the one fold `decided` is.
    val lefts = Flow.fromFunction<Either<L, R>, L> { either -> either.swap().decided() }.to(to)
    val right = guarded("divertLefts", buildSite()) { either: Either<L, R> -> either.decided() }
    val flow = Flow.create<Either<L, R>>().divertTo(lefts) { either -> either.isLeft() }.map { either -> right(either) }
    return Pipe(Node.Stage(Node.Hole, flow, Pekko))
}

fun <E, In, L : Any, R : Any> Pipe<E, In, Either<L, R>>.divertLefts(to: Sink<L, *>): Pipe<E, In, R> =
    via(Pipe.divertLefts(to))

/** Handles a declared failure only: a defect is nothing anyone declared, and still dies. */
fun <E, E2, A : Any> Pipe.Companion.catchAll(f: (E) -> Stream<E2, A>): Pipe<E2, A, A> {
    val recover: (E) -> Node = { e -> f(e).node }
    return Pipe(Node.CatchAll(Node.Hole, recover.erased()))
}

fun <E, E2, In, Out : Any> Pipe<E, In, Out>.catchAll(f: (E) -> Stream<E2, Out>): Pipe<E2, In, Out> =
    replacing(Pipe.catchAll(f))

/**
 * The declared failure as another one. `catchAll` says this too, and says recovery while it does: a
 * reader of `catchAll { Stream.failed(it.toIngestError()) }` reaches the end before learning that
 * nothing was recovered.
 */
fun <E, E2, A : Any> Pipe.Companion.mapError(f: (E) -> E2): Pipe<E2, A, A> =
    Pipe(Node.MapError(Node.Hole, f.erased(), buildSite()))

fun <E, E2, In, Out : Any> Pipe<E, In, Out>.mapError(f: (E) -> E2): Pipe<E2, In, Out> =
    replacing(Pipe.mapError(f))

/** ZIO's `orElse` rather than Pekko's: [other] takes over on a failure, not on an empty stream. */
fun <E, A : Any> Pipe.Companion.orElse(other: Stream<E, A>): Pipe<E, A, A> = catchAll<E, E, A> { other }

fun <E, In, Out : Any> Pipe<E, In, Out>.orElse(other: Stream<E, Out>): Pipe<E, In, Out> =
    replacing(Pipe.orElse(other))

/**
 * Pekko drops a `null` completion before any operator downstream can see it, so the guard belongs
 * inside the stage. The cast is what lets Kotlin look at a value whose type already claims it
 * cannot be null.
 *
 * The facts are attached to a stage that failed before the null is looked for, so that the
 * throwable the library raises below carries them in its message and the caller's own carries
 * them beside it.
 */
@Suppress("UNCHECKED_CAST")
internal fun <B : Any> CompletionStage<B>.orDieOnNull(element: Any, at: String): CompletionStage<B> =
    (this as CompletionStage<B?>)
        .whenComplete { _, thrown -> thrown?.unwrapped()?.describedBy("mapAsync", element, at) }
        .thenApply { b ->
            b ?: throw NullPointerException("${facts("mapAsync", element, at)}: the stage completed with null")
        }

/**
 * The side a `divertTo` predicate has already settled. Pekko's split keeps one element type on both
 * branches, so this is what the predicate knows and the compiler cannot.
 *
 * A fold rather than a cast: nothing is unchecked, and an element on the branch the predicate did not
 * choose dies where it is rather than being dropped by a partial function that does not match it.
 */
internal fun <A : Any> Either<*, A>.decided(): A =
    fold({ throw IllegalStateException("divertLefts: $it reached the branch the predicate did not send it to") }) { it }

/**
 * A recovery that sees a declared failure and nothing else, so every other throwable dies.
 *
 * The graph type is written out rather than left as the `Source` it is built from: `Flow`'s
 * `recoverWithRetries` asks for exactly that type where `Source`'s takes anything extending it.
 */
internal fun <E, A : Any> onDeclaredFailure(
    f: (E) -> Source<A, NotUsed>,
): PartialFunction<Throwable, Graph<SourceShape<A>, NotUsed>> =
    PFBuilder<Throwable, Graph<SourceShape<A>, NotUsed>>()
        .match(DeclaredFailure::class.java) { failure -> f(failure.declared()) }
        .build()
