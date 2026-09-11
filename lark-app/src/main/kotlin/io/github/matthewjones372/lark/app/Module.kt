package io.github.matthewjones372.lark.app

import kotlin.reflect.KType

internal class Node(
    val key: KType,
    val dependencies: List<KType>,
    val site: String?,
    val build: Wiring.(List<Any>) -> Any,
)

/** Recipes, keyed by the type each one builds. */
class Module private constructor(
    internal val nodes: Map<KType, Node>,
    internal val probes: List<Probe>,
) {

    /** [other]'s node wins wherever the two share a key, and takes the probes of that key with it. */
    operator fun plus(other: Module): Module =
        Module(nodes + other.nodes, probes.filterNot { it.key in other.nodes.keys } + other.probes)

    internal companion object {
        fun of(node: Node): Module = Module(mapOf(node.key to node), emptyList())

        fun of(nodes: Map<KType, Node>, probes: List<Probe>): Module = Module(nodes, probes)
    }
}

// Every lower-case run, so a generic argument loses its packages too.
private val qualifiers = Regex("""\b[a-z][A-Za-z0-9_]*(\.[a-z][A-Za-z0-9_]*)*\.""")

internal fun labelOf(key: KType): String = qualifiers.replace(key.toString(), "")

internal fun idOf(key: KType): String = labelOf(key).replace(Regex("[^A-Za-z0-9]"), "_")

@PublishedApi
internal fun module(
    key: KType,
    dependencies: List<KType>,
    build: Wiring.(List<Any>) -> Any,
): Module = Module.of(Node(key, dependencies, callSite(), build))

private val walker: StackWalker = StackWalker.getInstance()

/**
 * The first frame outside lark's own node factories.
 *
 * `single` and `singleOf` are inline, so their frames are the caller's already; `actor` and
 * `migrations` are not, and naming those would put every actor node in `lark-app-pekko`. A lark
 * factory is always a top-level function, so its frame is a file facade, which is what the `Kt`
 * distinguishes from a caller written as a class.
 */
internal fun callSite(): String? = walker.walk { frames ->
    frames.filter { !larkFactory(it.className) }
        .map { frame -> frame.fileName?.let { "$it:${frame.lineNumber}" } }
        .findFirst()
        .orElse(null)
}

private fun larkFactory(className: String): Boolean =
    className.startsWith("io.github.matthewjones372.lark.") &&
        className.substringAfterLast('.').endsWith("Kt")
