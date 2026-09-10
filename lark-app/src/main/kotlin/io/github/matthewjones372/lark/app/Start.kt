package io.github.matthewjones372.lark.app

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.matthewjones372.lark.ExitCase
import io.github.matthewjones372.lark.ResourceScope
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.lark.resourceScope
import io.github.matthewjones372.lark.timeoutOrNull
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** Why an application did not start. */
sealed class StartupError {

    /** The graph was faulty before anything was built. [provided] is what it did hold. */
    data class Unwireable(val errors: NonEmptyList<WiringError>, val provided: Set<KType> = emptySet()) :
        StartupError()

    /** A recipe declined, naming [reason]. */
    data class Refused(val key: KType, val reason: String) : StartupError()

    /** Nothing in the graph builds [key], and it was asked for. */
    data class NoSuchNode(val key: KType) : StartupError()

    /** [key] was built, and the probe named [name] did not answer inside its timeout. */
    data class Unready(val key: KType, val name: String) : StartupError()
}

/**
 * What a recipe is handed: somewhere to install a release, and a way to decline.
 *
 * Not also a `Raise<StartupError>`: `ResourceScope.bind` takes a `Resource<A>` and `Raise.bind` a
 * function too, and the two erase to one JVM signature.
 */
interface Wiring : ResourceScope {

    /** Ends the start, naming this node and [reason]. */
    fun refuse(reason: String): Nothing
}

/** Starts the graph, hands [block] the node it asks for, and gives everything back on the way out. */
inline fun <reified A : Any, B> Module.use(noinline block: (A) -> B): Either<StartupError, B> =
    use(typeOf<A>()) {
        @Suppress("UNCHECKED_CAST")
        block(it as A)
    }

@PublishedApi
internal fun <B> Module.use(root: KType, block: (Any) -> B): Either<StartupError, B> = either {
    val plan = validate().mapLeft { faults -> StartupError.Unwireable(faults, nodes.keys) }.bind()
    if (!nodes.containsKey(root)) raise(StartupError.NoSuchNode(root))

    val order = plan.layers.flatten()
    val health = HealthRegistry(probes)
    resourceScope {
        val releases = Releases()
        // One release on lark's scope, installed before anything is acquired: it decides the ExitCase,
        // and the order below is this graph's rather than whichever fork happened to finish first.
        onRelease { exit -> releases.release(order, exit) }
        val built = build(this@use, plan, releases, health)
        health.running()
        block(built.getValue(root))
    }
}

private fun Raise<StartupError>.build(
    module: Module,
    plan: Plan,
    releases: Releases,
    health: HealthRegistry,
): Map<KType, Any> =
    plan.layers.fold(mapOf<KType, Any>(typeOf<HealthRegistry>() to health)) { built, layer ->
        built + parMap(layer) { key ->
            val node = module.nodes.getValue(key)
            val value = node.build(NodeWiring(key, releases, this), node.dependencies.map(built::getValue))
            module.probes.filter { it.key == key }.forEach { probe ->
                if (!answers(probe, value)) raise(StartupError.Unready(key, probe.name))
            }
            health.started(key, value)
            key to value
        }
    }

/** Asked again up to [Probe.attempts] times, waiting out [Probe.interval] on the inherited clock. */
private fun Raise<StartupError>.answers(probe: Probe, value: Any): Boolean {
    repeat(probe.attempts) { attempt ->
        if (timeoutOrNull(probe.timeout) { probe.ask(value) } == true) return true
        if (attempt < probe.attempts - 1) clock.get().sleep(probe.interval)
    }
    return false
}

private class NodeWiring(
    private val key: KType,
    private val releases: Releases,
    private val raise: Raise<StartupError>,
) : Wiring {

    override fun onRelease(release: (ExitCase) -> Unit) = releases.add(key, release)

    override fun refuse(reason: String): Nothing = raise.raise(StartupError.Refused(key, reason))
}

/**
 * The releases of every node, kept by the node that installed them, because a layer's forks install at
 * once and acquisition order under `parMap` is whichever thread got there first.
 */
private class Releases {

    private val lock = ReentrantLock()
    private val byKey = mutableMapOf<KType, MutableList<(ExitCase) -> Unit>>()

    fun add(key: KType, release: (ExitCase) -> Unit) {
        lock.withLock { byKey.getOrPut(key) { mutableListOf() }.add(release) }
    }

    /** Reverse topological, so a node is given back only after everything that needed it. */
    fun release(order: List<KType>, exit: ExitCase) {
        val installed = lock.withLock { order.reversed().flatMap { byKey[it].orEmpty().reversed() } }
        val failures = installed.mapNotNull { runCatching { it(exit) }.exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}
