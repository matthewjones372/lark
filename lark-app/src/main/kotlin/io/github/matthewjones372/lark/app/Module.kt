package io.github.matthewjones372.lark.app

import kotlin.reflect.KType

internal class Node(
    val key: KType,
    val dependencies: List<KType>,
    val site: String?,
    val build: Wiring.(List<Any>) -> Any,
)

/** A key two modules both provided, and where each was written. */
internal class Shadow(val key: KType, val shadowed: String?, val wins: String?)

/** Recipes, keyed by the type each one builds. */
class Module private constructor(
    internal val nodes: Map<KType, Node>,
    internal val probes: List<Probe>,
    internal val shadows: List<Shadow> = emptyList(),
) {

    /** [other]'s node wins wherever the two share a key, and takes the probes of that key with it. */
    operator fun plus(other: Module): Module =
        Module(
            nodes + other.nodes,
            probes.filterNot { it.key in other.nodes.keys } + other.probes,
            // Kept rather than discarded: the merge cannot tell a deliberate override from a typo, and
            // only `overriding` knows which it was.
            shadows + other.shadows + collisionsWith(other),
        )

    private fun collisionsWith(other: Module): List<Shadow> =
        other.nodes.keys.filter { it in nodes }
            .map { Shadow(it, nodes.getValue(it).site, other.nodes.getValue(it).site) }

    /**
     * The same module, shadowing exactly what [kept] did.
     *
     * Named by what survives rather than by the keys to drop: a base module that already shadowed a
     * key by accident keeps saying so, and only the collisions this merge introduced are forgiven.
     */
    internal fun shadowing(kept: List<Shadow>): Module = Module(nodes, probes, kept)

    internal companion object {
        fun of(node: Node): Module = Module(mapOf(node.key to node), emptyList())

        fun of(nodes: Map<KType, Node>, probes: List<Probe>, shadows: List<Shadow> = emptyList()): Module =
            Module(nodes, probes, shadows)
    }
}

// Every lower-case run, so a generic argument loses its packages too.
private val qualifiers = Regex("""\b[a-z][A-Za-z0-9_]*(\.[a-z][A-Za-z0-9_]*)*\.""")

/**
 * What a `KType` renders as where kotlin-reflect is not on the classpath, once per type argument.
 *
 * This module depends on it nowhere else, and a report is no place to start: the name is still
 * right, and a build that prints "Pump (Kotlin reflection is not available) needs DataSource
 * (Kotlin reflection is not available)" has buried the sentence it came to say.
 */
private val unavailable = Regex(""" \(Kotlin reflection is not available\)""")

internal fun labelOf(key: KType): String = qualifiers.replace(unavailable.replace(key.toString(), ""), "")

internal fun idOf(key: KType): String = labelOf(key).replace(Regex("[^A-Za-z0-9]"), "_")

@PublishedApi
internal fun module(
    key: KType,
    dependencies: List<KType>,
    build: Wiring.(List<Any>) -> Any,
): Module = Module.of(Node(key, dependencies, callSite(), build))
