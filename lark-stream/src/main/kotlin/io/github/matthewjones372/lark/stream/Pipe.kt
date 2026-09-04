package io.github.matthewjones372.lark.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Flow

/**
 * The middle of a pipeline: `In` in, `Out` out, and a declared failure of `E` it can end with.
 *
 * Described and not yet running, and reusable — one of these is spliced into as many streams as
 * there are sources for it.
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
fun <E, E2 : E, A : Any, B : Any> Stream<E, A>.via(pipe: Pipe<E2, A, B>): Stream<E, B> = Stream(source.via(pipe.flow))

/** The way out to Pekko, open only once nothing is left that a graph would not understand. */
fun <In, Out : Any> Pipe<Nothing, In, Out>.toFlow(): Flow<In, Out, NotUsed> = flow
