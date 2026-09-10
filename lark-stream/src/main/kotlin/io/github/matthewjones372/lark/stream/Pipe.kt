package io.github.matthewjones372.lark.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Flow

/**
 * The middle of a pipeline: `In` in, `Out` out, and a declared failure of `E` it can end with.
 *
 * Described and not yet running, and reusable — one of these is spliced into as many streams as
 * there are sources for it. Every operator `Stream` has is here, and `Stream`'s are this one's
 * through [via].
 */
class Pipe<out E, in In, out Out : Any> internal constructor(
    // Pekko's Flow is a Java generic, so Kotlin reads both element types as invariant. What is
    // written to the input side is only ever read by the flow, and the output side is only ever
    // read from here, which is what the variance says and the compiler has no way to see.
    internal val flow: Flow<@UnsafeVariance In, @UnsafeVariance Out, NotUsed>,
) {
    companion object
}

fun <In, Out : Any> Pipe.Companion.from(flow: Flow<In, Out, NotUsed>): Pipe<Nothing, In, Out> = Pipe(flow)

/** The pipe that changes nothing, which is where a chain of operators starts. */
fun <A : Any> Pipe.Companion.identity(): Pipe<Nothing, A, A> = Pipe(Flow.create())

/** [next]'s failure joins this one's: a narrower `E2` slots in, as `absolve`'s `L` does. */
fun <E, E2 : E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.via(next: Pipe<E2, Out, Out2>): Pipe<E, In, Out2> =
    Pipe(flow.via(next.flow))

/** The pipe's elements become the stream's, and the failure it declares joins the stream's. */
fun <E, E2 : E, A : Any, B : Any> Stream<E, A>.via(pipe: Pipe<E2, A, B>): Stream<E, B> =
    Stream(source.via(pipe.flow))

/** The way out to Pekko, open only once nothing is left that a graph would not understand. */
fun <In, Out : Any> Pipe<Nothing, In, Out>.toFlow(): Flow<In, Out, NotUsed> = flow

/**
 * A pipe spliced onto a source, answering with the pipe's own failure rather than the stream's.
 *
 * `either`, `catchAll`, `mapError` and `orElse` replace what the stream declared instead of adding to
 * it, so `E2 : E` cannot type them. The failure is read off [pipe] rather than picked by the caller,
 * which is what stops a fifth operator naming a type nothing here produces.
 */
internal fun <E2, A : Any, B : Any> Stream<*, A>.replacing(pipe: Pipe<E2, A, B>): Stream<E2, B> =
    Stream(source.via(pipe.flow))

/** As above, for a pipe spliced onto a pipe. */
internal fun <E2, In, Out : Any, Out2 : Any> Pipe<*, In, Out>.replacing(
    next: Pipe<E2, Out, Out2>,
): Pipe<E2, In, Out2> = Pipe(flow.via(next.flow))

fun <A : Any, B : Any> Pipe.Companion.map(f: (A) -> B): Pipe<Nothing, A, B> {
    val body = guarded("map", buildSite(), f)
    return Pipe(Flow.create<A>().map { a -> body(a) })
}

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.map(f: (Out) -> Out2): Pipe<E, In, Out2> = via(Pipe.map(f))

/**
 * The starting form the failure type is read out of: `Pipe.mapOrFail<E, A, B> { }` is the pipe
 * twin of `Stream.from(rows).mapOrFail { }`.
 */
fun <E, A : Any, B : Any> Pipe.Companion.mapOrFail(f: Failing<E>.(A) -> B): Pipe<E, A, B> {
    val scope = Failing<E>()
    val body = guarded("mapOrFail", buildSite()) { a: A -> scope.f(a) }
    return Pipe(Flow.create<A>().map { a -> body(a) })
}

/**
 * As above, on a pipe that has yet to name a failure: this one reads the failure out of the body.
 *
 * The pair is `Stream.mapOrFail`'s, for its reason: Kotlin fixes the failure type from the receiver
 * before it looks at the `fail(e)` naming one. The more specific receiver decides, so a call site
 * never picks between them.
 */
// The two erase to one JVM signature, so one of them needs a name of its own down there. Kotlin
// callers never see it.
@JvmName("mapOrFailDeclaring")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapOrFail(f: Failing<F>.(Out) -> Out2): Pipe<F, In, Out2> =
    via(Pipe.mapOrFail(f))

/** As above, for a pipe whose failure type is already named. */
fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapOrFail(f: Failing<E>.(Out) -> Out2): Pipe<E, In, Out2> =
    via(Pipe.mapOrFail(f))

fun <A : Any> Pipe.Companion.filter(predicate: (A) -> Boolean): Pipe<Nothing, A, A> {
    val test = guarded("filter", buildSite(), predicate)
    return Pipe(Flow.create<A>().filter { a -> test(a) })
}

fun <E, In, Out : Any> Pipe<E, In, Out>.filter(predicate: (Out) -> Boolean): Pipe<E, In, Out> =
    via(Pipe.filter(predicate))
