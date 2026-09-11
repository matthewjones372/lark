package io.github.matthewjones372.lark.app

import arrow.core.getOrElse
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** Only the nodes [A] is reached through, so a test starts four of them rather than forty. */
inline fun <reified A : Any> Module.subgraph(): Module = subgraph(typeOf<A>())

@PublishedApi
internal fun Module.subgraph(root: KType): Module {
    val kept = reached(nodes, setOf(root), setOf(root))
    return Module.of(
        nodes.filterKeys { it in kept },
        probes.filter { it.key in kept },
        shadows.filter { it.key in kept },
    )
}

/** [plus], refusing a key this module does not already hold: a fake under a new key fakes nothing. */
fun Module.overriding(replacements: Module): Module {
    val unknown = replacements.nodes.keys - nodes.keys
    require(unknown.isEmpty()) {
        "overriding a key nothing provides: ${unknown.joinToString { labelOf(it) }}"
    }
    return (this + replacements).deliberate(replacements.nodes.keys)
}

/** Starts the graph for a test and gives it back afterwards, whatever the block did. */
inline fun <reified A : Any, B> testApp(module: Module, noinline block: (A) -> B): B =
    module.use(block).getOrElse { error(it.describe()) }

internal tailrec fun reached(
    nodes: Map<KType, Node>,
    frontier: Set<KType>,
    found: Set<KType>,
): Set<KType> {
    val next = frontier.flatMap { nodes[it]?.dependencies.orEmpty() }.toSet() - found
    return if (next.isEmpty()) found else reached(nodes, next, found + next)
}
