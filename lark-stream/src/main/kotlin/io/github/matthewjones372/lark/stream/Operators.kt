package io.github.matthewjones372.lark.stream

import arrow.core.Either
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletionStage

/**
 * Up to [parallelism] stages run at once and their results keep the input's order.
 *
 * A stage that completes with `null` dies here. Pekko's own `mapAsync` documents
 * that such an element "is not passed downstream", and a lookup lifted into a
 * future is the easiest thing in Kotlin to write and the hardest to notice.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapAsync(parallelism: Int, f: (A) -> CompletionStage<B>): Stream<E, B> =
    via(Pipe.mapAsync(parallelism, f))

/** The declared failure becomes the last element, as a `Left`, leaving none for the type to carry. */
fun <E, A : Any> Stream<E, A>.either(): Stream<Nothing, Either<E, A>> = through(Pipe.either<E, A>())

/** A `Left` fails the stream with what it holds; a `Right` carries on as the element. */
fun <E, L : E, R : Any> Stream<E, Either<L, R>>.absolve(): Stream<E, R> = via(Pipe.absolve<E, L, R>())

/** Every `Left` reaches [to] and every `Right` carries on: `divertTo` with no predicate to write. */
fun <E, L : Any, R : Any> Stream<E, Either<L, R>>.divertLefts(to: Sink<L, *>): Stream<E, R> =
    via(Pipe.divertLefts(to))

/** Handles a declared failure only: a defect is nothing anyone declared, and still dies. */
fun <E, E2, A : Any> Stream<E, A>.catchAll(f: (E) -> Stream<E2, A>): Stream<E2, A> = through(Pipe.catchAll(f))

/** ZIO's `orElse` rather than Pekko's: [other] takes over on a failure, not on an empty stream. */
fun <E, A : Any> Stream<E, A>.orElse(other: Stream<E, A>): Stream<E, A> = through(Pipe.orElse(other))

/**
 * A stream that ends having emitted nothing fails with [error] instead; one that emitted is untouched.
 *
 * Pekko's `orElse` switches to the alternative only where the primary completed without an element, so
 * a failure of its own reaches `run` as itself. On `Stream` alone: a pipe is spliced into a source it
 * cannot see, so it has no way to know whether that source emitted.
 */
fun <E : E2, E2, A : Any> Stream<E, A>.orFailIfEmpty(error: E2): Stream<E2, A> =
    // The alternative is deferred because a plain `Source.failed` fails at materialisation, which would
    // fail every stream through here rather than the empty ones.
    Stream(source.orElse(Source.lazySource { Source.failed<A>(DeclaredFailure(error)) }))
