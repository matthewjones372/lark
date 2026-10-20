package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions

/** One document in the stack, under the name a reader knows it by. */
class Layer internal constructor(val name: String, val config: Config)

/**
 * The documents a service reads, kept apart.
 *
 * `withFallback` keeps the winning value and forgets the one it beat, so a merged [Config] cannot say
 * what a setting overrode — the same thing `Module.plus` does to a shadowed node, and answered the
 * same way. [layers] are in precedence order, the first winning.
 */
class Layered internal constructor(val layers: List<Layer>) {

    /**
     * The merge, resolved once over the whole stack, because a substitution may cross a layer. It
     * remembers these layers, so a section read from it can report what each setting overrode.
     */
    val config: Config by lazy {
        LayeredConfig(
            this,
            layers.map { it.config }.reduceOrNull { above, below -> above.withFallback(below) }
                ?.resolve()
                ?: ConfigFactory.empty(),
        )
    }
}

/**
 * The merge itself, and the [layered] it came from.
 *
 * A section depends on `Config` alone, so one written by hand still starts; the layers ride along on
 * the value for the report rather than as a second node it would have to require. Equal to the merge,
 * though the merge's own `equals` does not know to answer the same.
 */
internal class LayeredConfig(val layered: Layered, private val merged: Config) : Config by merged {

    override fun equals(other: Any?): Boolean = merged == ((other as? LayeredConfig)?.merged ?: other)

    override fun hashCode(): Int = merged.hashCode()

    override fun toString(): String = merged.toString()
}

/**
 * What `ConfigFactory.load()` reads, as layers rather than as the merge of them, in its order: the
 * `CONFIG_FORCE_` variables when `-Dconfig.override_with_env_vars` asks for them, system properties,
 * the application layer, and the reference.
 *
 * Not `defaultReference()`, which merges system properties into the reference before handing it
 * over: a report built on that says `reference.conf` holds a value that came from `-D`.
 */
fun layeredConfig(): Layered = Layered(
    listOfNotNull(environmentOverrides(), "system properties" to ConfigFactory.systemProperties())
        .plus(application())
        .plus("reference.conf" to ConfigFactory.defaultReferenceUnresolved())
        .map { (name, config) -> Layer(name, config) },
)

/** `defaultOverrides()` merges these into the system properties, which would credit `-D` with an env var. */
private fun environmentOverrides(): Pair<String, Config>? =
    if (System.getProperty("config.override_with_env_vars").toBoolean()) {
        "environment overrides" to ConfigFactory.systemEnvironmentOverrides()
    } else {
        null
    }

/**
 * `defaultApplication()`, split where it merges: a strategy or a `config.file`, `.resource` or `.url`
 * replaces the whole layer, and otherwise `application.conf` beats `.json`, which beats `.properties`.
 */
private fun application(): List<Pair<String, Config>> {
    val strategy = System.getProperty("config.strategy")
    val replacement = REPLACEMENTS.firstNotNullOfOrNull { System.getProperty(it) }
    return when {
        strategy != null -> listOf(strategy to ConfigFactory.defaultApplication())
        replacement != null -> listOf(replacement to ConfigFactory.parseApplicationReplacement().get())
        else -> APPLICATION.map { name -> name to ConfigFactory.parseResources(name) }
    }
}

private val REPLACEMENTS = listOf("config.resource", "config.file", "config.url")

private val APPLICATION = listOf("application.conf", "application.json", "application.properties")

/** The stack written out, for a test that would rather not have three files. Highest precedence first. */
fun layeredConfigOf(vararg layers: Pair<String, Config>): Layered =
    Layered(layers.map { (name, config) -> Layer(name, config) })

/**
 * Every setting the stack resolved to, and for one that more than one layer supplied, what it beat.
 *
 * Each layer is resolved against the whole merged document rather than against itself, which is what
 * actually happened to it — a layer holding `${petshop.db.name}` for a name another layer supplies
 * cannot be read on its own. Unresolved is allowed rather than fatal: a report that refuses to
 * explain a broken file is the opposite of what it is for.
 */
fun Layered.origins(secrets: Secrets = Secrets.default): Origins {
    val merged = config
    val resolved = layers.map { Layer(it.name, it.config.resolvedWithin(merged)) }
    return Origins(
        merged.origins(secrets).map { origin -> origin.copy(overrides = resolved.beaten(origin, secrets)) },
    )
}

/**
 * Grafted into the merge and resolved there, not `resolveWith(merged)`: Typesafe Config throws
 * `BugOrBroken` resolving a delayed merge, `a = "x"` then `a = ${?ENV}`, against any document but its own.
 */
private fun Config.resolvedWithin(merged: Config): Config =
    merged.withValue(GRAFT, root()).resolve(LENIENT).getConfig(GRAFT)

/** The first layer under the winner that also held the path, which is the value the merge dropped. */
private fun List<Layer>.beaten(origin: Origin, secrets: Secrets): Overridden? =
    filter { it.config.hasPathOrNull(origin.path) }
        .drop(1)
        .firstOrNull()
        ?.let { layer ->
            val value = layer.config.getValue(origin.path)
            // Masked by the winner's own verdict: a report that hides the new password and prints the
            // old one has published a secret that is probably still in use somewhere.
            Overridden(if (secrets.redacts(origin.path)) MASK else value.rendered(), value.origin().where())
        }

private const val GRAFT = "lark-layered-origins"

private val LENIENT: ConfigResolveOptions = ConfigResolveOptions.defaults().setAllowUnresolved(true)
