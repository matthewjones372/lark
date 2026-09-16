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

    /** The merge, resolved once over the whole stack, because a substitution may cross a layer. */
    val config: Config by lazy {
        layers.map { it.config }.reduceOrNull { above, below -> above.withFallback(below) }
            ?.resolve()
            ?: ConfigFactory.empty()
    }
}

/**
 * What a service reads at start-up, as layers rather than as the merge of them.
 *
 * Not `defaultReference()`, which merges system properties into the reference before handing it
 * over: a report built on that says `reference.conf` holds a value that came from `-D`.
 */
fun layeredConfig(): Layered = layeredConfigOf(
    "system properties" to ConfigFactory.defaultOverrides(),
    "application.conf" to ConfigFactory.parseResources("application.conf"),
    "reference.conf" to ConfigFactory.parseResources("reference.conf"),
)

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
    val resolved = layers.map { Layer(it.name, it.config.resolveWith(merged, LENIENT)) }
    return Origins(
        merged.origins(secrets).map { origin -> origin.copy(overrides = resolved.beaten(origin, secrets)) },
    )
}

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

private val LENIENT: ConfigResolveOptions = ConfigResolveOptions.defaults().setAllowUnresolved(true)
