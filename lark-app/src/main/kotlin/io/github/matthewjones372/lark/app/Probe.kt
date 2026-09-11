package io.github.matthewjones372.lark.app

import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration

/** A question a node answers about itself, and the name the answer is reported under. */
class Probe internal constructor(
    val name: String,
    val key: KType,
    val timeout: Duration,
    val critical: Boolean,
    val attempts: Int,
    val interval: Duration,
    internal val ask: (Any) -> Boolean,
)

/**
 * A node is started when its probe answers, not when its recipe returns: a pool with no connection and
 * a consumer with no assignment have both been constructed and neither can serve anything.
 */
inline fun <reified A : Any> Module.probe(
    name: String,
    timeout: Duration,
    critical: Boolean = true,
    attempts: Int = 1,
    interval: Duration = Duration.ZERO,
    noinline ask: (A) -> Boolean,
): Module = probed(name, typeOf<A>(), timeout, critical, attempts, interval) { ask(it as A) }

@PublishedApi
internal fun Module.probed(
    name: String,
    key: KType,
    timeout: Duration,
    critical: Boolean,
    attempts: Int,
    interval: Duration,
    ask: (Any) -> Boolean,
): Module = Module.of(nodes, probes + Probe(name, key, timeout, critical, attempts, interval, ask), shadows)
