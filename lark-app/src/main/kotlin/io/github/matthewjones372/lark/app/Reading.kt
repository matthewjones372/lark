package io.github.matthewjones372.lark.app

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import kotlin.time.Duration

/**
 * A configuration read, which answers with every complaint rather than the first.
 *
 * A read that cannot answer records why and hands back a value nobody looks at, so the block runs to
 * the end and names every path that is wrong. That is what a service wants on a bad deploy: one
 * message listing four missing variables, not four deploys.
 */
class Reading internal constructor(private val config: Config, private val prefix: String) {

    internal val faults: MutableList<ConfigError> = mutableListOf()

    private fun pathTo(path: String) = if (prefix.isEmpty()) path else "$prefix.$path"

    private fun <A> read(path: String, expected: String, parse: (String) -> A?, absent: A): A {
        val full = pathTo(path)
        val raw = config.at(full) ?: return absent.also { faults += ConfigError.Missing(full) }
        return parse(raw) ?: absent.also { faults += ConfigError.NotA(full, expected, raw) }
    }

    fun string(path: String): String = read(path, "a String", { it }, "")

    fun int(path: String): Int = read(path, "an Int", String::toIntOrNull, 0)

    fun long(path: String): Long = read(path, "a Long", String::toLongOrNull, 0)

    fun boolean(path: String): Boolean = read(path, "true or false", String::toBooleanStrictOrNull, false)

    fun duration(path: String): Duration = read(path, "a Duration", Duration::parseOrNull, Duration.ZERO)

    /** [read]'s value where the path is set, and [default] where it is not. Never a fault. */
    fun <A> optional(path: String, default: A, read: Reading.(String) -> A): A =
        if (config.at(pathTo(path)) == null) default else read(path)

    /** The same read, under [prefix], so a section is written once rather than in every path. */
    fun <A> section(prefix: String, block: Reading.() -> A): A {
        val nested = Reading(config, pathTo(prefix))
        return nested.block().also { faults += nested.faults }
    }
}

/**
 * Reads [block] against this source, answering with the value or with everything that was wrong.
 *
 * [block] is run once. A read that fails records its fault and answers with a value the result is
 * discarded with, so a constructor that validates its arguments may throw — that throw is caught and
 * the faults are what comes back, because they are what the reader has to fix first.
 */
fun <A> Config.read(block: Reading.() -> A): Either<NonEmptyList<ConfigError>, A> {
    val reading = Reading(this, "")
    val value = runCatching { reading.block() }
    val faults = reading.faults.toNonEmptyListOrNull()
    return when {
        faults != null -> faults.left()
        else -> value.getOrThrow().right()
    }
}

/**
 * A section of the configuration as a node, read by the module that needs it.
 *
 * The type is the safety: `DbConfig` is built by code the compiler checks, from a section nothing
 * outside this module names. A module added later brings its own reading with it, and a service's
 * configuration is the sum of its modules rather than one type that has to know about all of them.
 */
inline fun <reified A : Any> configured(
    section: String = "",
    noinline read: Reading.() -> A,
): Module = single { config: Config ->
    config.read { if (section.isEmpty()) read() else section(section) { read() } }
        .getOrElse { faults -> refuse(faults.joinToString("; ") { fault -> fault.describe() }) }
}
