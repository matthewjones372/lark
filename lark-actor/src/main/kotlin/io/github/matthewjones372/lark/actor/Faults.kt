package io.github.matthewjones372.lark.actor

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a test runtime does to a message one actor tells another, each by probability from the order's seed: lose it,
 * deliver it twice, or deliver it up to [maxDelay] later in the test's time. A tell from the test itself is never
 * touched, and nor are timers.
 */
data class Faults(
    val drop: Double = 0.0,
    val duplicate: Double = 0.0,
    val delay: Double = 0.0,
    val maxDelay: Duration = 1.seconds,
) {
    init {
        listOf(drop, duplicate, delay).forEach { require(it in 0.0..1.0) { "a probability is from 0 to 1, not $it" } }
        require(drop + duplicate + delay <= 1.0) { "drop, duplicate and delay add up to more than 1" }
        require(maxDelay.isPositive()) { "maxDelay must be positive, was $maxDelay" }
    }

    internal val any: Boolean get() = drop + duplicate + delay > 0.0
}
