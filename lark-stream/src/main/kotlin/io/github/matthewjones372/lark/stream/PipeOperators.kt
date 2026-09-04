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
    Pipe(Flow.create<A>().mapAsync(parallelism) { a -> f(a).orDieOnNull() })

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapAsync(
    parallelism: Int,
    f: (Out) -> CompletionStage<Out2>,
): Pipe<E, In, Out2> = via(Pipe.mapAsync(parallelism, f))

/** The declared failure becomes the last element, as a `Left`, leaving none for the type to carry. */
fun <E, A : Any> Pipe.Companion.either(): Pipe<Nothing, A, Either<E, A>> {
    val rights: Flow<A, Either<E, A>, NotUsed> = Flow.create<A>().map { a -> Either.Right(a) }
    return Pipe(
        rights.recoverWithRetries(1, onDeclaredFailure { e: E -> Source.single<Either<E, A>>(Either.Left(e)) }),
    )
}

fun <E, In, Out : Any> Pipe<E, In, Out>.either(): Pipe<Nothing, In, Either<E, Out>> = through(Pipe.either<E, Out>())

/** A `Left` fails the pipe with what it holds; a `Right` carries on as the element. */
fun <E, L : E, R : Any> Pipe.Companion.absolve(): Pipe<E, Either<L, R>, R> =
    Pipe(
        Flow.create<Either<L, R>>()
            .map { either -> either.fold({ left -> throw DeclaredFailure(left) }, { right -> right }) },
    )

fun <E, In, L : E, R : Any> Pipe<E, In, Either<L, R>>.absolve(): Pipe<E, In, R> = via(Pipe.absolve<E, L, R>())

/** Every `Left` reaches [to] and every `Right` carries on: `divertTo` with no predicate to write. */
fun <L : Any, R : Any> Pipe.Companion.divertLefts(to: Sink<L, *>): Pipe<Nothing, Either<L, R>, R> {
    // Flipped, so that both branches read their element through the one cast below.
    val lefts = Flow.fromFunction<Either<L, R>, L> { either -> either.swap().decided() }.to(to)
    return Pipe(
        Flow.create<Either<L, R>>().divertTo(lefts) { either -> either.isLeft() }.map { either -> either.decided() },
    )
}

fun <E, In, L : Any, R : Any> Pipe<E, In, Either<L, R>>.divertLefts(to: Sink<L, *>): Pipe<E, In, R> =
    via(Pipe.divertLefts(to))

/** Handles a declared failure only: a defect is nothing anyone declared, and still dies. */
fun <E, E2, A : Any> Pipe.Companion.catchAll(f: (E) -> Stream<E2, A>): Pipe<E2, A, A> =
    Pipe(Flow.create<A>().recoverWithRetries(1, onDeclaredFailure { e: E -> f(e).source }))

fun <E, E2, In, Out : Any> Pipe<E, In, Out>.catchAll(f: (E) -> Stream<E2, Out>): Pipe<E2, In, Out> =
    through(Pipe.catchAll(f))

/** ZIO's `orElse` rather than Pekko's: [other] takes over on a failure, not on an empty stream. */
fun <E, A : Any> Pipe.Companion.orElse(other: Stream<E, A>): Pipe<E, A, A> = catchAll<E, E, A> { other }

fun <E, In, Out : Any> Pipe<E, In, Out>.orElse(other: Stream<E, Out>): Pipe<E, In, Out> =
    through(Pipe.orElse(other))

/**
 * Pekko drops a `null` completion before any operator downstream can see it, so the guard belongs
 * inside the stage. The cast is what lets Kotlin look at a value whose type already claims it
 * cannot be null.
 */
@Suppress("UNCHECKED_CAST")
private fun <B : Any> CompletionStage<B>.orDieOnNull(): CompletionStage<B> =
    (this as CompletionStage<B?>).thenApply { b ->
        b ?: throw NullPointerException("mapAsync: the stage completed with null")
    }

/**
 * The side a `divertTo` predicate has already settled. Pekko's split keeps one element type on both
 * branches, so this is what the predicate knows and the compiler cannot.
 */
@Suppress("UNCHECKED_CAST")
private fun <A : Any> Either<*, A>.decided(): A = (this as Either.Right<A>).value

/**
 * A recovery that sees a declared failure and nothing else, so every other throwable dies.
 *
 * The graph type is written out rather than left as the `Source` it is built from: `Flow`'s
 * `recoverWithRetries` asks for exactly that type where `Source`'s takes anything extending it.
 */
private fun <E, A : Any> onDeclaredFailure(
    f: (E) -> Source<A, NotUsed>,
): PartialFunction<Throwable, Graph<SourceShape<A>, NotUsed>> =
    PFBuilder<Throwable, Graph<SourceShape<A>, NotUsed>>()
        .match(DeclaredFailure::class.java) { failure -> f(failure.declared()) }
        .build()
