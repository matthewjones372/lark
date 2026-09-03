package io.github.matthewjones372.lark.stream

import arrow.core.raise.Raise
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Source

/**
 * A stream of `A` that can end with a declared failure of `E`, described and
 * not yet running.
 */
class Stream<out E, out A : Any> internal constructor(
    // Pekko's Source is a Java generic, so Kotlin reads its element as
    // invariant. Every operator below only reads from it, so widening A here
    // is safe in a place the compiler has no way to see that.
    internal val source: Source<@UnsafeVariance A, NotUsed>,
) {
    companion object
}

/**
 * A declared failure on Pekko's failure channel, unwrapped only by `run`.
 *
 * No stack trace: the error is the value being carried, and filling one in
 * would charge every declared failure for a diagnostic nobody reads.
 */
internal class DeclaredFailure(val error: Any?) : RuntimeException(null, null, false, false)

/**
 * The error a [DeclaredFailure] carries, read back as the stream's own `E`.
 *
 * The cast is unchecked because the channel the error travelled in carries a
 * `Throwable` and nothing narrower; the stream's own type is what says what
 * came back out of it.
 */
@Suppress("UNCHECKED_CAST")
internal fun <E> DeclaredFailure.declared(): E = error as E

/**
 * The scope `mapOrFail` runs in: a `Raise<E>`, so `bind`, `ensure` and lark's
 * own combinators are in reach of an element body, and `fail` returns Nothing,
 * so it sits after an Elvis.
 */
class Failing<in E> internal constructor() : Raise<E> {

    /** The failure travels as it always has: the wrapper only `run` unwraps. */
    override fun raise(r: E): Nothing = throw DeclaredFailure(r)

    /** The name dipper gave [raise], kept so every caller written against it still reads. */
    fun fail(error: E): Nothing = raise(error)
}

fun <A : Any> Stream.Companion.from(source: Source<A, NotUsed>): Stream<Nothing, A> = Stream(source)

fun <A : Any> Stream.Companion.from(elements: Iterable<A>): Stream<Nothing, A> = Stream(Source.from(elements))

fun <E> Stream.Companion.fail(error: E): Stream<E, Nothing> = Stream(Source.failed(DeclaredFailure(error)))

fun Stream.Companion.empty(): Stream<Nothing, Nothing> = Stream(Source.empty())

fun <E, A : Any, B : Any> Stream<E, A>.map(f: (A) -> B): Stream<E, B> = Stream(source.map { a -> f(a) })

/**
 * `f` may answer with `fail(e)`, which ends the stream with the `E` it names.
 *
 * There are two of these because Kotlin fixes a type variable the moment the
 * receiver constrains it: on a `Stream<Nothing, A>` a single signature would
 * pin the failure type to `Nothing` before the `fail(e)` naming it is looked
 * at. This one reads the failure type out of the body, and the one below keeps
 * the failure a stream already declares. The more specific receiver decides,
 * so a call site never picks between them.
 */
// The two erase to one JVM signature, so one of them needs a name of its own
// down there. Kotlin callers never see it.
@JvmName("mapOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapOrFail(f: Failing<F>.(A) -> B): Stream<F, B> {
    val scope = Failing<F>()
    return Stream(source.map { a -> scope.f(a) })
}

/** As above, for a stream whose failure type is already named. */
fun <E, A : Any, B : Any> Stream<E, A>.mapOrFail(f: Failing<E>.(A) -> B): Stream<E, B> {
    val scope = Failing<E>()
    return Stream(source.map { a -> scope.f(a) })
}

fun <E, A : Any> Stream<E, A>.filter(predicate: (A) -> Boolean): Stream<E, A> =
    Stream(source.filter { a -> predicate(a) })

/** The way out to Pekko, open only once nothing is left that a sink would not understand. */
fun <A : Any> Stream<Nothing, A>.toSource(): Source<A, NotUsed> = source
