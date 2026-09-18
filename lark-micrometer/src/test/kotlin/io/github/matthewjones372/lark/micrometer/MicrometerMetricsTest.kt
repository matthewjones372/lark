package io.github.matthewjones372.lark.micrometer

import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.gauge
import io.github.matthewjones372.lark.histogram
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.metricTagged
import io.github.matthewjones372.lark.metrics
import io.github.matthewjones372.lark.parMap
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/** What a measurement looks like once it reaches a registry, asserted against a real one. */
class MicrometerMetricsTest {

    private fun <A> recording(registry: SimpleMeterRegistry, block: () -> A): A =
        metrics.locally(MicrometerMetrics(registry), block)

    @Test
    fun `a counter arrives with its name and its total`() {
        val registry = SimpleMeterRegistry()

        recording(registry) {
            counter("petshop.adoptions").increment()
            counter("petshop.adoptions").increment(by = 2.0)
        }

        registry.counter("petshop.adoptions").count() shouldBe 3.0
    }

    @Test
    fun `a tag in scope is a tag on the meter`() {
        val registry = SimpleMeterRegistry()

        recording(registry) {
            metricTagged("species" to "tortoise") { counter("petshop.adoptions").increment() }
        }

        withClue("one series per species, which is what a tag is for") {
            registry.counter("petshop.adoptions", "species", "tortoise").count() shouldBe 1.0
        }
    }

    @Test
    fun `a gauge reads back what it was set to, through a registry that pulls`() {
        val registry = SimpleMeterRegistry()

        recording(registry) {
            gauge("petshop.queue.depth").set(4.0)
            gauge("petshop.queue.depth").set(2.0)
        }

        withClue("Micrometer asks a gauge for its value; this holds a number for it to ask") {
            registry.get("petshop.queue.depth").gauge().value() shouldBe 2.0
        }
    }

    @Test
    fun `a histogram arrives as a distribution`() {
        val registry = SimpleMeterRegistry()

        recording(registry) {
            histogram("petshop.adopt.ms").record(12.0)
            histogram("petshop.adopt.ms").record(30.0)
        }

        registry.get("petshop.adopt.ms").summary().let { taken ->
            taken.count() shouldBe 2L
            taken.totalAmount() shouldBe 42.0
        }
    }

    @Test
    fun `a fork measures into the same meter as its opener`() {
        val registry = SimpleMeterRegistry()

        recording(registry) {
            metricTagged("species" to "tortoise") {
                parMap(listOf(1, 2, 3)) { counter("petshop.adoptions").increment() }
            }
        }

        registry.counter("petshop.adoptions", "species", "tortoise").count() shouldBe 3.0
    }

    @Test
    fun `nothing is bound in main and the classpath still answers`() {
        withClue("the service file is the whole of the setup, so it is asserted not assumed") {
            metrics.get().shouldBeInstanceOf<MicrometerMetrics>()
        }
    }
}
