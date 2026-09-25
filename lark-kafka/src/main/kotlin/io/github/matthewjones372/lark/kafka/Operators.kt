package io.github.matthewjones372.lark.kafka

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.stream.Failing
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.filter
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.mapConcat
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.mapPar
import io.github.matthewjones372.lark.stream.mapParOrFail

// lark-stream's operators with the body on the value and the offset carried through. Named apart from
// them because the overloads cannot be told apart: with both packages imported, `map` would be ambiguous.

fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapRecord(f: (A) -> B): Stream<E, Committed<B>> =
    map { c: Committed<A> -> c.carrying(c.annotated(f)) }

/** A filtered record is committed with the next record on its partition that is not. */
fun <E, A : Any> Stream<E, Committed<A>>.filterRecord(predicate: (A) -> Boolean): Stream<E, Committed<A>> =
    filter { c: Committed<A> -> c.annotated(predicate) }

/** The record's offset goes on the last element; a record that expands to none commits as a filtered one. */
fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapConcatRecord(f: (A) -> Iterable<B>): Stream<E, Committed<B>> =
    mapConcat { c: Committed<A> ->
        val elements = c.annotated(f).toList()
        elements.mapIndexed { i, b -> Committed(b, c.position, c.offset.takeIf { i == elements.lastIndex }) }
    }

@JvmName("mapRecordOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, Committed<A>>.mapRecordOrFail(
    f: Failing<F>.(A) -> B,
): Stream<F, Committed<B>> =
    mapOrFail<F, Committed<A>, Committed<B>> { c -> c.carrying(c.annotated { f(this, it) }) }

fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapRecordOrFail(f: Failing<E>.(A) -> B): Stream<E, Committed<B>> =
    mapOrFail<E, Committed<A>, Committed<B>> { c -> c.carrying(c.annotated { f(this, it) }) }

fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapParRecord(
    parallelism: Int,
    f: Raise<E>.(A) -> B,
): Stream<E, Committed<B>> =
    mapPar(parallelism) { c: Committed<A> -> c.carrying(c.annotated { f(this, it) }) }

@JvmName("mapParRecordOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, Committed<A>>.mapParRecordOrFail(
    parallelism: Int,
    f: Raise<F>.(A) -> B,
): Stream<F, Committed<B>> =
    mapParOrFail<F, Committed<A>, Committed<B>>(parallelism) { c -> c.carrying(c.annotated { f(this, it) }) }

fun <E, A : Any, B : Any> Stream<E, Committed<A>>.mapParRecordOrFail(
    parallelism: Int,
    f: Raise<E>.(A) -> B,
): Stream<E, Committed<B>> =
    mapParOrFail<E, Committed<A>, Committed<B>>(parallelism) { c -> c.carrying(c.annotated { f(this, it) }) }
