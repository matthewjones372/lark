package io.github.matthewjones372.lark.assert

/** Asserts this string contains [part], and returns it. */
infix fun String?.shouldContain(part: String): String = checked("a string containing ${quoted(part)}") { part in it }

/** Asserts this string does not contain [part], and returns it. */
infix fun String?.shouldNotContain(part: String): String =
    checked("a string without ${quoted(part)}") { part !in it }

/** Asserts this string starts with [prefix], and returns it. */
infix fun String?.shouldStartWith(prefix: String): String =
    checked("a string starting with ${quoted(prefix)}") { it.startsWith(prefix) }

/** Asserts this string ends with [suffix], and returns it. */
infix fun String?.shouldEndWith(suffix: String): String =
    checked("a string ending with ${quoted(suffix)}") { it.endsWith(suffix) }

/** Asserts this string has something in it besides whitespace, and returns it. */
fun String?.shouldNotBeBlank(): String = checked("a string that is not blank") { it.isNotBlank() }

/** Asserts the whole of this string matches [pattern], and returns it. */
infix fun String?.shouldMatch(pattern: String): String = shouldMatch(Regex(pattern))

/** The same, for a pattern already compiled. */
infix fun String?.shouldMatch(pattern: Regex): String =
    checked("a string matching /${pattern.pattern}/") { pattern.matches(it) }

private inline fun String?.checked(wanted: String, holds: (String) -> Boolean): String {
    if (this == null || !holds(this)) mismatch(expected = null, actual = this?.let(::quoted), expectedText = wanted)
    return this
}

private fun quoted(text: String): String = "\"$text\""
