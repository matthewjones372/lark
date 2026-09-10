package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.ResourceScope
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal class Node(
    val key: KType,
    val dependencies: List<KType>,
    val build: ResourceScope.(List<Any>) -> Any,
)

/** Recipes, keyed by the type each one builds. */
class Module private constructor(internal val nodes: Map<KType, Node>) {

    /** [other]'s node wins wherever the two share a key. */
    operator fun plus(other: Module): Module = Module(nodes + other.nodes)

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
        fun of(node: Node): Module = Module(mapOf(node.key to node))
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
    build: ResourceScope.(List<Any>) -> Any,
): Module = Module.of(Node(key, dependencies, build))

/** Its type argument is written out: a bare lambda also fits the one-dependency overload, as its `it`. */
inline fun <reified A : Any> single(noinline build: ResourceScope.() -> A): Module =
    module(typeOf<A>(), emptyList()) { build() }

/** A recipe, with what it needs as its parameters. */
inline fun <reified A : Any, reified D1 : Any> single(noinline build: ResourceScope.(D1) -> A): Module =
    module(typeOf<A>(), listOf(typeOf<D1>())) { deps ->
        val (d1) = deps
        build(d1 as D1)
    }

inline fun <reified A : Any, reified D1 : Any, reified D2 : Any> single(
    noinline build: ResourceScope.(D1, D2) -> A,
): Module = module(typeOf<A>(), listOf(typeOf<D1>(), typeOf<D2>())) { deps ->
    val (d1, d2) = deps
    build(d1 as D1, d2 as D2)
}

inline fun <reified A : Any, reified D1 : Any, reified D2 : Any, reified D3 : Any> single(
    noinline build: ResourceScope.(D1, D2, D3) -> A,
): Module = module(typeOf<A>(), listOf(typeOf<D1>(), typeOf<D2>(), typeOf<D3>())) { deps ->
    val (d1, d2, d3) = deps
    build(d1 as D1, d2 as D2, d3 as D3)
}

inline fun <reified A : Any, reified D1 : Any, reified D2 : Any, reified D3 : Any, reified D4 : Any> single(
    noinline build: ResourceScope.(D1, D2, D3, D4) -> A,
): Module = module(typeOf<A>(), listOf(typeOf<D1>(), typeOf<D2>(), typeOf<D3>(), typeOf<D4>())) { deps ->
    val (d1, d2, d3, d4) = deps
    build(d1 as D1, d2 as D2, d3 as D3, d4 as D4)
}

inline fun <
    reified A : Any,
    reified D1 : Any,
    reified D2 : Any,
    reified D3 : Any,
    reified D4 : Any,
    reified D5 : Any,
    > single(
    noinline build: ResourceScope.(D1, D2, D3, D4, D5) -> A,
): Module = module(
    typeOf<A>(),
    listOf(typeOf<D1>(), typeOf<D2>(), typeOf<D3>(), typeOf<D4>(), typeOf<D5>()),
) { deps ->
    val (d1, d2, d3, d4, d5) = deps
    build(d1 as D1, d2 as D2, d3 as D3, d4 as D4, d5 as D5)
}
