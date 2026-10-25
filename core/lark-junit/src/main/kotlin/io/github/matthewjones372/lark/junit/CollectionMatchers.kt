@file:MustUseReturnValues

package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.FailureSeam
import io.github.matthewjones372.lark.assert.missingAny
import io.github.matthewjones372.lark.assert.notExactly
import io.github.matthewjones372.lark.assert.notOfSize

// The matchers on a collection or a map, as Matchers.kt's: each returns Expectations, naming the test's line.

/** Holds if [element] is among this collection's elements; fails carrying the elements. */
infix fun <T> Iterable<T>.shouldContain(element: T): Expectations =
    if (element in this) passed(1) else mismatchedHere(null, toList(), "a collection containing $element")

/** Holds if [element] is not among this collection's elements. */
infix fun <T> Iterable<T>.shouldNotContain(element: T): Expectations =
    if (element !in this) passed(1) else mismatchedHere(null, toList(), "a collection without $element")

/** Holds if this has exactly [elements], each as many times, in any order; fails carrying the elements found. */
infix fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(elements: Iterable<T>): Expectations =
    if (counted(this) == counted(elements)) {
        passed(1)
    } else {
        mismatchedHere(null, toList(), "${elements.toList()} in any order")
    }

/** Holds if this has exactly [elements], each as many times, in any order. */
fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(vararg elements: T): Expectations =
    shouldContainExactlyInAnyOrder(elements.asList())

/** Holds if this has exactly [elements], in their order; fails carrying both lists for an IDE's diff. */
@OptIn(FailureSeam::class)
infix fun <T> Iterable<T>.shouldContainExactly(elements: Iterable<T>): Expectations = verdict(notExactly(elements))

/** Holds if this has exactly [elements], in their order. */
fun <T> Iterable<T>.shouldContainExactly(vararg elements: T): Expectations = shouldContainExactly(elements.asList())

/** Holds if this has [size] elements; fails naming how many it has, and which. */
@OptIn(FailureSeam::class)
infix fun Iterable<*>.shouldHaveSize(size: Int): Expectations = verdict(notOfSize(size))

/** Holds if this has no elements. */
fun Iterable<*>.shouldBeEmpty(): Expectations = if (none()) passed(1) else mismatchedHere(null, toList(), "no elements")

/** Holds if this has an element. */
fun Iterable<*>.shouldNotBeEmpty(): Expectations =
    if (any()) passed(1) else mismatchedHere(null, toList(), "at least one element")

/** Holds if this has every one of [elements], in any order and among others; fails naming those missing. */
infix fun <T> Iterable<T>.shouldContainAll(elements: Iterable<T>): Expectations {
    val missing = elements.filterNot { it in this }
    return if (missing.isEmpty()) {
        passed(1)
    } else {
        mismatchedHere(null, "${toList()}, missing $missing", "all of ${elements.toList()}")
    }
}

/** Holds if this has an entry for [key]; fails carrying the map. */
infix fun <K> Map<K, *>.shouldContainKey(key: K): Expectations =
    if (key in this) passed(1) else mismatchedHere(null, this, "a map with the key $key")

/** Holds if this has no entry for [key]. */
infix fun <K> Map<K, *>.shouldNotContainKey(key: K): Expectations =
    if (key !in this) passed(1) else mismatchedHere(null, this, "a map without the key $key")

/** Holds if this maps [entry]'s key to its value. */
infix fun <K, V> Map<K, V>.shouldContain(entry: Pair<K, V>): Expectations =
    if (containsKey(entry.first) && get(entry.first) == entry.second) {
        passed(1)
    } else {
        mismatchedHere(null, this, "a map with ${entry.first}=${entry.second}")
    }

/** Holds if this has every entry of [entries], among others; fails naming those missing. */
@OptIn(FailureSeam::class)
infix fun <K, V> Map<K, V>.shouldContainAll(entries: Map<K, V>): Expectations = verdict(missingAny(entries))

/** Holds if this has no entries. */
fun Map<*, *>.shouldBeEmpty(): Expectations = if (isEmpty()) passed(1) else mismatchedHere(null, this, "no entries")

/** Holds if this has an entry. */
fun Map<*, *>.shouldNotBeEmpty(): Expectations =
    if (isNotEmpty()) passed(1) else mismatchedHere(null, this, "at least one entry")

private fun <T> counted(elements: Iterable<T>): Map<T, Int> = elements.groupingBy { it }.eachCount()
