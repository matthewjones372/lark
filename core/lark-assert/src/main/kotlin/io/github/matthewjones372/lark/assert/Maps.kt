package io.github.matthewjones372.lark.assert

/** Asserts this has no entries, and returns it. */
fun <K, V, M : Map<K, V>> M.shouldBeEmpty(): M {
    if (isNotEmpty()) mismatch(expected = null, actual = this, expectedText = "no entries")
    return this
}

/** Asserts this has an entry, and returns it. */
fun <K, V, M : Map<K, V>> M.shouldNotBeEmpty(): M {
    if (isEmpty()) mismatch(expected = null, actual = this, expectedText = "at least one entry")
    return this
}

/** Asserts this has an entry for [key], and returns it. */
infix fun <K, V, M : Map<K, V>> M.shouldContainKey(key: K): M {
    if (key !in this) mismatch(expected = null, actual = this, expectedText = "a map with the key $key")
    return this
}

/** Asserts this has no entry for [key], and returns it. */
infix fun <K, V, M : Map<K, V>> M.shouldNotContainKey(key: K): M {
    if (key in this) mismatch(expected = null, actual = this, expectedText = "a map without the key $key")
    return this
}

/** Asserts this maps [entry]'s key to its value, and returns it. */
infix fun <K, V, M : Map<K, V>> M.shouldContain(entry: Pair<K, V>): M {
    if (!holds(entry.first, entry.second)) {
        mismatch(expected = null, actual = this, expectedText = "a map with ${entry.first}=${entry.second}")
    }
    return this
}

/** Asserts this has every entry of [entries], among others, and returns it. */
infix fun <K, V, M : Map<K, V>> M.shouldContainAll(entries: Map<K, V>): M {
    val missing = entries.filterNot { (key, value) -> holds(key, value) }
    if (missing.isNotEmpty()) {
        mismatch(expected = null, actual = "$this, missing $missing", expectedText = "all of $entries")
    }
    return this
}

/** Asserts this has exactly the entries of [entries], and returns it. An IDE shows the two maps side by side. */
infix fun <K, V, M : Map<K, V>> M.shouldContainExactly(entries: Map<K, V>): M {
    if (this != entries) mismatch(expected = entries, actual = this)
    return this
}

// A key mapped to null is told from a missing key.
private fun <K, V> Map<K, V>.holds(key: K, value: V): Boolean = containsKey(key) && get(key) == value
