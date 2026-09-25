package io.github.matthewjones372.lark.stream

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.VirtualThreads
import java.util.concurrent.Executor

/**
 * Up to [parallelism] element bodies run at once, each on a virtual thread of its own and in a
 * `Raise<E>`; the results keep the input's order, as [mapAsync]'s do.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapPar(parallelism: Int, f: Raise<E>.(A) -> B): Stream<E, B> =
    via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Stream<E, A>.mapPar(parallelism: Int, on: Executor, f: Raise<E>.(A) -> B): Stream<E, B> =
    via(Pipe.mapPar(parallelism, on, f))

/** The starting form: a pipe of bodies, each on a virtual thread of its own. */
fun <E, A : Any, B : Any> Pipe.Companion.mapPar(parallelism: Int, f: Raise<E>.(A) -> B): Pipe<E, A, B> =
    forked(parallelism, VirtualThreads, f)

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Pipe.Companion.mapPar(
    parallelism: Int,
    on: Executor,
    f: Raise<E>.(A) -> B,
): Pipe<E, A, B> = forked(parallelism, on, f)

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapPar(
    parallelism: Int,
    f: Raise<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapPar(
    parallelism: Int,
    on: Executor,
    f: Raise<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = via(Pipe.mapPar(parallelism, on, f))

/**
 * As [mapPar], on a stream that has yet to name a failure: this one reads the failure out of the body.
 *
 * It has a name of its own, `mapOrFail`'s suffix, because Kotlin fixes the type variable before it
 * looks at the body. Under one name, the form that reads `F` from a `raise` wins on a
 * `Stream<Nothing, A>`, and a body that never raises leaves it nothing to read.
 */
// The two erase to one JVM signature, so one of them needs a name of its own down there. Kotlin
// callers never see it.
@JvmName("mapParOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapParOrFail(parallelism: Int, f: Raise<F>.(A) -> B): Stream<F, B> =
    via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
@JvmName("mapParOrFailDeclaringOn")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Raise<F>.(A) -> B,
): Stream<F, B> = via(Pipe.mapPar(parallelism, on, f))

/** As above, for a stream whose failure type is already named: [mapPar] under the name that says it may fail. */
fun <E, A : Any, B : Any> Stream<E, A>.mapParOrFail(parallelism: Int, f: Raise<E>.(A) -> B): Stream<E, B> =
    mapPar(parallelism, f)

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Stream<E, A>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Raise<E>.(A) -> B,
): Stream<E, B> = mapPar(parallelism, on, f)

/** As the stream pair above, on a pipe that has yet to name a failure. */
@JvmName("mapParOrFailPipeDeclaring")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapParOrFail(
    parallelism: Int,
    f: Raise<F>.(Out) -> Out2,
): Pipe<F, In, Out2> = via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
@JvmName("mapParOrFailPipeDeclaringOn")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Raise<F>.(Out) -> Out2,
): Pipe<F, In, Out2> = via(Pipe.mapPar(parallelism, on, f))

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapParOrFail(
    parallelism: Int,
    f: Raise<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = mapPar(parallelism, f)

/** The same, with every body run on [on]. */
fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Raise<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = mapPar(parallelism, on, f)

private fun <E, A : Any, B : Any> forked(parallelism: Int, on: Executor, f: Raise<E>.(A) -> B): Pipe<E, A, B> =
    Pipe(Node.MapPar(Node.Hole, parallelism, on, f.erased(), buildSite()))
