package io.github.matthewjones372.lark.app

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import kotlin.time.Duration

/** Why a name could not be read as what it was asked for. */
sealed class ConfigError {

    data class Missing(val name: String) : ConfigError()

    data class NotA(val name: String, val expected: String, val value: String) : ConfigError()
}

/**
 * What a process was started with. A node takes one rather than calling `System.getenv`, so a test of
 * what a service does with its configuration touches no environment at all.
 */
interface Sys {

    fun env(name: String): String?

    fun property(name: String): String?

    fun env(): Map<String, String>
}

object RealSys : Sys {

    override fun env(name: String): String? = System.getenv(name)

    override fun property(name: String): String? = System.getProperty(name)

    override fun env(): Map<String, String> = System.getenv()
}

class FakeSys(
    private val env: Map<String, String>,
    private val properties: Map<String, String> = emptyMap(),
) : Sys {

    override fun env(name: String): String? = env[name]

    override fun property(name: String): String? = properties[name]

    override fun env(): Map<String, String> = env
}

/** Null where the name is not set, for a value with a default rather than a requirement. */
fun Sys.optional(name: String): String? = env(name)

fun Sys.required(name: String): Either<ConfigError, String> =
    env(name)?.right() ?: ConfigError.Missing(name).left()

fun Sys.int(name: String): Either<ConfigError, Int> = read(name, "an Int", String::toIntOrNull)

fun Sys.boolean(name: String): Either<ConfigError, Boolean> =
    read(name, "true or false") { it.toBooleanStrictOrNull() }

fun Sys.duration(name: String): Either<ConfigError, Duration> =
    read(name, "a Duration") { Duration.parseOrNull(it) }

private fun <A> Sys.read(name: String, expected: String, parse: (String) -> A?): Either<ConfigError, A> =
    required(name).flatMap { raw ->
        parse(raw)?.right() ?: ConfigError.NotA(name, expected, raw).left()
    }
