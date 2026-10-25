@file:MustUseReturnValues
@file:OptIn(FailureSeam::class)

package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.FailureSeam
import io.github.matthewjones372.lark.assert.empty
import io.github.matthewjones372.lark.assert.holding
import io.github.matthewjones372.lark.assert.holdingKey
import io.github.matthewjones372.lark.assert.missing
import io.github.matthewjones372.lark.assert.missingAny
import io.github.matthewjones372.lark.assert.missingInAnyOrder
import io.github.matthewjones372.lark.assert.missingKey
import io.github.matthewjones372.lark.assert.notEmpty
import io.github.matthewjones372.lark.assert.notExactly
import io.github.matthewjones372.lark.assert.notOfSize

// The matchers on a collection or a map, as Matchers.kt's: each returns Expectations on lark-assert's check.

/** Holds if [element] is among this collection's elements; fails carrying the elements. */
infix fun <T> Iterable<T>.shouldContain(element: T): Expectations = verdict(missing(element))

/** Holds if [element] is not among this collection's elements. */
infix fun <T> Iterable<T>.shouldNotContain(element: T): Expectations = verdict(holding(element))

/** Holds if this has exactly [elements], each as many times, in any order; fails carrying the elements found. */
infix fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(elements: Iterable<T>): Expectations =
    verdict(missingInAnyOrder(elements))

/** Holds if this has exactly [elements], each as many times, in any order. */
fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(vararg elements: T): Expectations =
    shouldContainExactlyInAnyOrder(elements.asList())

/** Holds if this has exactly [elements], in their order; fails carrying both lists for an IDE's diff. */
infix fun <T> Iterable<T>.shouldContainExactly(elements: Iterable<T>): Expectations = verdict(notExactly(elements))

/** Holds if this has exactly [elements], in their order. */
fun <T> Iterable<T>.shouldContainExactly(vararg elements: T): Expectations = shouldContainExactly(elements.asList())

/** Holds if this has [size] elements; fails naming how many it has, and which. */
infix fun Iterable<*>.shouldHaveSize(size: Int): Expectations = verdict(notOfSize(size))

/** Holds if this has no elements. */
fun Iterable<*>.shouldBeEmpty(): Expectations = verdict(notEmpty())

/** Holds if this has an element. */
fun Iterable<*>.shouldNotBeEmpty(): Expectations = verdict(empty())

/** Holds if this has every one of [elements], in any order and among others; fails naming those missing. */
infix fun <T> Iterable<T>.shouldContainAll(elements: Iterable<T>): Expectations = verdict(missingAny(elements))

/** Holds if this has an entry for [key]; fails carrying the map. */
infix fun <K> Map<K, *>.shouldContainKey(key: K): Expectations = verdict(missingKey(key))

/** Holds if this has no entry for [key]. */
infix fun <K> Map<K, *>.shouldNotContainKey(key: K): Expectations = verdict(holdingKey(key))

/** Holds if this maps [entry]'s key to its value. */
infix fun <K, V> Map<K, V>.shouldContain(entry: Pair<K, V>): Expectations = verdict(missing(entry))

/** Holds if this has every entry of [entries], among others; fails naming those missing. */
infix fun <K, V> Map<K, V>.shouldContainAll(entries: Map<K, V>): Expectations = verdict(missingAny(entries))

/** Holds if this has no entries. */
fun Map<*, *>.shouldBeEmpty(): Expectations = verdict(notEmpty())

/** Holds if this has an entry. */
fun Map<*, *>.shouldNotBeEmpty(): Expectations = verdict(empty())
