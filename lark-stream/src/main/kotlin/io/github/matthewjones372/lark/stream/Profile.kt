package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.Histogram
import io.github.matthewjones372.lark.Metrics
import io.github.matthewjones372.lark.NoMetrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder

/** What one stage of a measured run did. `busy` and `waiting` are means in milliseconds, where it reported them. */
data class StageProfile(
    val elements: Long,
    val busyMillis: Double? = null,
    val waitingMillis: Double? = null,
    /** The sampled busy time in all, which a stage's share of the run is worked out from. */
    val busySampledMillis: Double = 0.0,
)

/** A measured run's stages, keyed by `step`: each stage's place in the order data moves through the description. */
class Profile(val stages: Map<Int, StageProfile>) {

    private val busyInAll = stages.values.sumOf { it.busySampledMillis }

    /**
     * The stage's part of the time every stage spent in its own body. Every stage samples one element in
     * the same number, so sampled time is in proportion to the time itself. Null for a stage with no body.
     */
    fun share(step: Int): Double? =
        stages[step]?.takeIf { it.busyMillis != null && busyInAll > 0 }?.let { it.busySampledMillis / busyInAll }
}

/**
 * [Metrics] that keeps what a measured run reports, and hands it on to [also]: `Measured(name, profiler)`,
 * run it, then [profile] and `render(profile = …)` to see where the time went.
 */
class Profiler(private val also: Metrics = NoMetrics) : Metrics {

    private class Tally {
        val sum = DoubleAdder()
        val count = LongAdder()

        fun add(value: Double) {
            sum.add(value)
            count.increment()
        }

        fun mean(): Double? = count.sum().takeIf { it > 0 }?.let { sum.sum() / it }
    }

    private val tallies = ConcurrentHashMap<Pair<String, Int>, Tally>()

    private fun tally(name: String, tags: Map<String, String>): Tally? =
        tags["step"]?.toIntOrNull()?.let { step -> tallies.computeIfAbsent(name to step) { Tally() } }

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val kept = tally(name, tags)
        val passed = also.counter(name, tags)
        return Counter { by ->
            kept?.sum?.add(by)
            passed.increment(by)
        }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = also.gauge(name, tags)

    override fun histogram(name: String, tags: Map<String, String>): Histogram {
        val kept = tally(name, tags)
        val passed = also.histogram(name, tags)
        return Histogram { value ->
            kept?.add(value)
            passed.record(value)
        }
    }

    /** What every stage reported so far. */
    fun profile(): Profile {
        val steps = tallies.keys.map { it.second }.toSet()
        val of = { name: String, step: Int -> tallies[name to step] }
        return Profile(
            steps.associateWith { step ->
                StageProfile(
                    elements = of(ELEMENTS, step)?.sum?.sum()?.toLong() ?: 0,
                    busyMillis = of(BUSY, step)?.mean(),
                    waitingMillis = of(WAITING, step)?.mean(),
                    busySampledMillis = of(BUSY, step)?.sum?.sum() ?: 0.0,
                )
            },
        )
    }

    private companion object {
        const val ELEMENTS = "lark.stream.elements"
        const val BUSY = "lark.stream.busy"
        const val WAITING = "lark.stream.waiting"
    }
}
