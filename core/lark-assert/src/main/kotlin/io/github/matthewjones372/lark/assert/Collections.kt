package io.github.matthewjones372.lark.assert

/** Asserts this has no elements, and returns it. */
fun <T, C : Iterable<T>> C.shouldBeEmpty(): C {
    if (any()) mismatch(expected = null, actual = toList(), expectedText = "no elements")
    return this
}

/** Asserts this has an element, and returns it. */
fun <T, C : Iterable<T>> C.shouldNotBeEmpty(): C {
    if (none()) mismatch(expected = null, actual = toList(), expectedText = "at least one element")
    return this
}

/** Asserts this has [size] elements, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldHaveSize(size: Int): C {
    val found = count()
    if (found != size) mismatch(expected = null, actual = "$found in ${toList()}", expectedText = "$size elements")
    return this
}

/** Asserts this has [element] among its elements, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldContain(element: T): C {
    if (element !in this) {
        mismatch(expected = null, actual = toList(), expectedText = "a collection containing $element")
    }
    return this
}

/** Asserts this does not have [element] among its elements, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldNotContain(element: T): C {
    if (element in this) mismatch(expected = null, actual = toList(), expectedText = "a collection without $element")
    return this
}

/** Asserts this has every one of [elements], in any order and among others, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldContainAll(elements: Iterable<T>): C {
    val missing = elements.filterNot { it in this }
    if (missing.isNotEmpty()) {
        val wanted = "all of ${elements.toList()}"
        mismatch(expected = null, actual = "${toList()}, missing $missing", expectedText = wanted)
    }
    return this
}

/** Asserts this has every one of [elements], in any order and among others, and returns it. */
fun <T, C : Iterable<T>> C.shouldContainAll(vararg elements: T): C = shouldContainAll(elements.asList())

/** Asserts this has exactly [elements], in their order, and returns it. An IDE shows the two lists side by side. */
infix fun <T, C : Iterable<T>> C.shouldContainExactly(elements: Iterable<T>): C {
    if (toList() != elements.toList()) mismatch(expected = elements.toList(), actual = toList())
    return this
}

/** Asserts this has exactly [elements], in their order, and returns it. */
fun <T, C : Iterable<T>> C.shouldContainExactly(vararg elements: T): C = shouldContainExactly(elements.asList())

/** Asserts this has exactly [elements], each as many times, in any order, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldContainExactlyInAnyOrder(elements: Iterable<T>): C {
    if (counted(this) != counted(elements)) {
        mismatch(expected = null, actual = toList(), expectedText = "${elements.toList()} in any order")
    }
    return this
}

/** Asserts this has exactly [elements], each as many times, in any order, and returns it. */
fun <T, C : Iterable<T>> C.shouldContainExactlyInAnyOrder(vararg elements: T): C =
    shouldContainExactlyInAnyOrder(elements.asList())

/** Asserts every element of this is one of [elements], and each of those is in this, and returns it. */
infix fun <T, C : Iterable<T>> C.shouldContainOnly(elements: Iterable<T>): C {
    if (toSet() != elements.toSet()) {
        mismatch(expected = null, actual = toList(), expectedText = "only and all of ${elements.toList()}")
    }
    return this
}

private fun <T> counted(elements: Iterable<T>): Map<T, Int> = elements.groupingBy { it }.eachCount()
