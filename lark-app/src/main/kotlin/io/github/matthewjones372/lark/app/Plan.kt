package io.github.matthewjones372.lark.app

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import kotlin.reflect.KType

/** Why a graph cannot be built. */
sealed class WiringError {

    /** No module provides [key], and [neededBy] asked for it. */
    data class Missing(val key: KType, val neededBy: KType) : WiringError()

    /** One cycle, each key needing the next, ending where it began. */
    data class Cycle(val path: List<KType>) : WiringError()
}

/** The order a graph starts in: a layer's nodes depend only on the layers before it. */
class Plan internal constructor(val layers: List<List<KType>>)

/** Every fault in the graph, or the order it starts in. Runs no recipe. */
fun Module.validate(): Either<NonEmptyList<WiringError>, Plan> {
    val missing = nodes.values.flatMap { node ->
        node.dependencies.filterNot(nodes::containsKey).map { WiringError.Missing(it, node.key) }
    }

    // Every missing key at once: a graph is usually short of a module, not of one node.
    return missing.toNonEmptyListOrNull()?.left()
        ?: layers(nodes.mapValues { (_, node) -> node.dependencies.toSet() }, emptyList())
            .mapLeft { nonEmptyListOf(WiringError.Cycle(cycleIn(it))) }
            .map(::Plan)
}

/** The errors grouped by the key that is missing, with what asked for it under each. */
fun NonEmptyList<WiringError>.report(): String {
    val missing = filterIsInstance<WiringError.Missing>()
        .groupBy { it.key }
        .entries
        .sortedBy { (key, _) -> key.toString() }
        .map { (key, asked) ->
            val consumers = asked.map { "❯     for ${labelOf(it.neededBy)}" }.sorted()
            (listOf("❯ missing ${labelOf(key)}") + consumers).joinToString("\n")
        }

    val cycles = filterIsInstance<WiringError.Cycle>()
        .map { cycle -> "❯ cycle ${cycle.path.joinToString(" → ", transform = ::labelOf)}" }

    return (listOf("lark-app wiring error") + missing + cycles).joinToString("\n\n")
}

/** Kahn's algorithm, a layer at a time; what is left when nothing is ready holds the cycle. */
private tailrec fun layers(
    remaining: Map<KType, Set<KType>>,
    planned: List<List<KType>>,
): Either<Map<KType, Set<KType>>, List<List<KType>>> {
    val ready = remaining.filterValues { it.isEmpty() }.keys.sortedBy { it.toString() }
    return when {
        remaining.isEmpty() -> planned.right()

        ready.isEmpty() -> remaining.left()

        else -> layers(
            (remaining - ready.toSet()).mapValues { (_, needs) -> needs - ready.toSet() },
            planned + listOf(ready),
        )
    }
}

/** Every key left needs another that is also left, so following one far enough repeats. */
private fun cycleIn(stalled: Map<KType, Set<KType>>): List<KType> =
    walk(stalled, stalled.keys.sortedBy { it.toString() }.first(), emptyList())

private tailrec fun walk(graph: Map<KType, Set<KType>>, at: KType, seen: List<KType>): List<KType> =
    when (at) {
        in seen -> seen.dropWhile { it != at } + at
        else -> walk(graph, graph.getValue(at).sortedBy { it.toString() }.first(), seen + at)
    }
