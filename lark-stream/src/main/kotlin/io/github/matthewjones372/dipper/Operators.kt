package io.github.matthewjones372.dipper

import arrow.core.Either
import org.apache.pekko.NotUsed
import org.apache.pekko.japi.pf.PFBuilder
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import scala.PartialFunction
import java.util.concurrent.CompletionStage

/**
 * Up to [parallelism] stages run at once and their results keep the input's order.
 *
 * A stage that completes with `null` dies here. Pekko's own `mapAsync` documents
 * that such an element "is not passed downstream", and a lookup lifted into a
 * future is the easiest thing in Kotlin to write and the hardest to notice.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapAsync(parallelism: Int, f: (A) -> CompletionStage<B>): Stream<E, B> =
    Stream(source.mapAsync(parallelism) { a -> f(a).orDieOnNull() })

/** The declared failure becomes the last element, as a `Left`, leaving none for the type to carry. */
fun <E, A : Any> Stream<E, A>.either(): Stream<Nothing, Either<E, A>> {
    val rights: Source<Either<E, A>, NotUsed> = source.map { a -> Either.Right(a) }
    return Stream(
        rights.recoverWithRetries(1, onDeclaredFailure { e: E -> Source.single<Either<E, A>>(Either.Left(e)) }),
    )
}

/** A `Left` fails the stream with what it holds; a `Right` carries on as the element. */
fun <E, L : E, R : Any> Stream<E, Either<L, R>>.absolve(): Stream<E, R> =
    Stream(source.map { either -> either.fold({ left -> throw DeclaredFailure(left) }, { right -> right }) })

/** Every `Left` reaches [to] and every `Right` carries on: `divertTo` with no predicate to write. */
fun <E, L : Any, R : Any> Stream<E, Either<L, R>>.divertLefts(to: Sink<L, *>): Stream<E, R> {
    // Flipped, so that both branches read their element through the one cast below.
    val lefts = Flow.fromFunction<Either<L, R>, L> { either -> either.swap().decided() }.to(to)
    return Stream(source.divertTo(lefts) { either -> either.isLeft() }.map { either -> either.decided() })
}

/** Handles a declared failure only: a defect is nothing anyone declared, and still dies. */
fun <E, E2, A : Any> Stream<E, A>.catchAll(f: (E) -> Stream<E2, A>): Stream<E2, A> =
    Stream(source.recoverWithRetries(1, onDeclaredFailure { e: E -> f(e).source }))

/** ZIO's `orElse` rather than Pekko's: [other] takes over on a failure, not on an empty stream. */
fun <E, A : Any> Stream<E, A>.orElse(other: Stream<E, A>): Stream<E, A> = catchAll { other }

/**
 * Pekko drops a `null` completion before any operator downstream can see it, so
 * the guard belongs inside the stage. The cast is what lets Kotlin look at a
 * value whose type already claims it cannot be null.
 */
@Suppress("UNCHECKED_CAST")
private fun <B : Any> CompletionStage<B>.orDieOnNull(): CompletionStage<B> =
    (this as CompletionStage<B?>).thenApply { b ->
        b ?: throw NullPointerException("mapAsync: the stage completed with null")
    }

/**
 * The side a `divertTo` predicate has already settled. Pekko's split keeps one
 * element type on both branches, so this is what the predicate knows and the
 * compiler cannot.
 */
@Suppress("UNCHECKED_CAST")
private fun <A : Any> Either<*, A>.decided(): A = (this as Either.Right<A>).value

/** A recovery that sees a declared failure and nothing else, so every other throwable dies. */
private fun <E, A : Any> onDeclaredFailure(
    f: (E) -> Source<A, NotUsed>,
): PartialFunction<Throwable, Source<A, NotUsed>> =
    PFBuilder<Throwable, Source<A, NotUsed>>()
        .match(DeclaredFailure::class.java) { failure -> f(failure.declared()) }
        .build()
