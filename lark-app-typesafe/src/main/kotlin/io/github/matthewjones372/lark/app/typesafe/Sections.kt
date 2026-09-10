package io.github.matthewjones372.lark.app.typesafe

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/** A path that could not be read, in Typesafe Config's own words — which name the file and the line. */
data class ConfigFault(val why: String)

/**
 * A read of a HOCON section that answers with every fault rather than the first.
 *
 * Nothing here wraps a value: [of] hands the caller the real [Config] and whatever it returns is the
 * value, so substitution, merging, `getDuration`, `getStringList`, `getMemorySize` and `getConfig`
 * all still work. What is added is the one thing Typesafe Config does not do — carrying on after a
 * missing path so that a bad file is one message rather than one deploy per fault.
 */
class Reading internal constructor(val config: Config) {

    internal val faults: MutableList<ConfigFault> = mutableListOf()

    /**
     * [read] against the section, or [absent] where it threw — with the exception's own message kept,
     * because it names the origin and Typesafe Config words it better than this could.
     */
    fun <A> of(absent: A, read: Config.() -> A): A =
        try {
            config.read()
        } catch (missing: ConfigException) {
            faults += ConfigFault(missing.message ?: missing.toString())
            absent
        }

    fun string(path: String): String = of("") { getString(path) }

    fun int(path: String): Int = of(0) { getInt(path) }

    fun long(path: String): Long = of(0) { getLong(path) }

    fun boolean(path: String): Boolean = of(false) { getBoolean(path) }

    fun strings(path: String): List<String> = of(emptyList()) { getStringList(path) }

    /** HOCON's own duration — `30s`, `5 minutes` — as `kotlin.time`'s, which is what lark takes. */
    fun duration(path: String): Duration = of(Duration.ZERO) { getDuration(path).toKotlinDuration() }

    /** HOCON's own size — `512M`, `2 GiB` — in bytes. */
    fun bytes(path: String): Long = of(0) { getMemorySize(path).toBytes() }

    /** A nested section, read the same way. Its faults are this one's. */
    fun <A> section(path: String, block: Reading.() -> A): A {
        val nested = Reading(of(config.root().toConfig()) { getConfig(path) })
        return nested.block().also { faults += nested.faults }
    }
}

/**
 * Reads [block] against this section. A failed read records the fault and answers with a value the
 * result is discarded with, so the block runs to the end and names everything that is wrong. A
 * constructor that validates its arguments may throw on those discarded values; that throw is caught
 * and the faults are what comes back, because they are what has to be fixed first.
 */
fun <A> Config.reading(block: Reading.() -> A): Either<NonEmptyList<ConfigFault>, A> {
    val reading = Reading(this)
    val value = runCatching { reading.block() }
    return reading.faults.toNonEmptyListOrNull()?.left() ?: value.getOrThrow().right()
}

/**
 * The section at [path] as a node, read by the module that needs it.
 *
 * The module owns its own settings: one added later brings its reading with it rather than editing a
 * root type that has to know about every section. A section that cannot be read refuses the start,
 * naming every fault at once.
 */
inline fun <reified A : Any> config(path: String, noinline read: Reading.() -> A): Module =
    single { root: Config ->
        root.reading { section(path) { read() } }
            .getOrElse { faults -> refuse(faults.joinToString("; ") { fault -> fault.why }) }
    }

@Deprecated("Named config, which is what it reads", ReplaceWith("config(path, read)"))
inline fun <reified A : Any> configured(path: String, noinline read: Reading.() -> A): Module =
    config(path, read)
