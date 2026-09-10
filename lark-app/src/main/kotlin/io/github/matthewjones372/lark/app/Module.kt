package io.github.matthewjones372.lark.app

import kotlin.reflect.KType

internal class Node(
    val key: KType,
    val dependencies: List<KType>,
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

    /** The graph as mermaid, one edge per dependency. */
    fun render(): String {
        val keys = (nodes.keys + nodes.values.flatMap { it.dependencies }).distinct().sortedBy { it.toString() }
        val declarations = keys.map { "    ${idOf(it)}[${labelOf(it)}]" }
        val edges = nodes.values.sortedBy { it.key.toString() }.flatMap { node ->
            node.dependencies.sortedBy { it.toString() }.map { "    ${idOf(it)} --> ${idOf(node.key)}" }
        }
        return (listOf("graph TD") + declarations + edges).joinToString("\n")
    }

    internal companion object {
        fun of(node: Node): Module = Module(mapOf(node.key to node), emptyList())

        fun of(nodes: Map<KType, Node>, probes: List<Probe>): Module = Module(nodes, probes)
    }
}

// Every lower-case run, so a generic argument loses its packages too.
private val qualifiers = Regex("""\b[a-z][A-Za-z0-9_]*(\.[a-z][A-Za-z0-9_]*)*\.""")

internal fun labelOf(key: KType): String = qualifiers.replace(key.toString(), "")

private fun idOf(key: KType): String = labelOf(key).replace(Regex("[^A-Za-z0-9]"), "_")

@PublishedApi
internal fun module(
    key: KType,
    dependencies: List<KType>,
    build: Wiring.(List<Any>) -> Any,
): Module = Module.of(Node(key, dependencies, build))
