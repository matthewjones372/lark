package io.github.matthewjones372.lark.stream

import org.apache.pekko.stream.javadsl.Flow

/**
 * Each element expanded into a stream of its own, one after another. The inner stream may fail, which
 * is why it is a [Stream] rather than a `Source`: a paged fetch that can fail would otherwise go back
 * to the untyped side, which is the shape this exists to delete.
 */
fun <E, A : Any, B : Any> Pipe.Companion.flatMapConcat(f: (A) -> Stream<E, B>): Pipe<E, A, B> {
    val build = guarded("flatMapConcat", buildSite(), f)
    return Pipe(Flow.create<A>().flatMapConcat { a -> build(a).source })
}

fun <E, E2 : E, In, Out : Any, B : Any> Pipe<E, In, Out>.flatMapConcat(
    f: (Out) -> Stream<E2, B>,
): Pipe<E, In, B> = through(Pipe.flatMapConcat(f))

fun <E, E2 : E, A : Any, B : Any> Stream<E, A>.flatMapConcat(f: (A) -> Stream<E2, B>): Stream<E, B> =
    through(Pipe.flatMapConcat(f))

/** As [flatMapConcat], with up to [breadth] inner streams running and their elements interleaved. */
fun <E, A : Any, B : Any> Pipe.Companion.flatMapMerge(breadth: Int, f: (A) -> Stream<E, B>): Pipe<E, A, B> {
    val build = guarded("flatMapMerge", buildSite(), f)
    return Pipe(Flow.create<A>().flatMapMerge(breadth) { a -> build(a).source })
}

fun <E, E2 : E, In, Out : Any, B : Any> Pipe<E, In, Out>.flatMapMerge(
    breadth: Int,
    f: (Out) -> Stream<E2, B>,
): Pipe<E, In, B> = through(Pipe.flatMapMerge(breadth, f))

fun <E, E2 : E, A : Any, B : Any> Stream<E, A>.flatMapMerge(
    breadth: Int,
    f: (A) -> Stream<E2, B>,
): Stream<E, B> = through(Pipe.flatMapMerge(breadth, f))

/** [flatMapConcat] under the name a reader of a stream built by `map` into a fetch looks for first. */
fun <E, E2 : E, B : Any> Stream<E, Stream<E2, B>>.flatten(): Stream<E, B> = flatMapConcat { inner -> inner }
