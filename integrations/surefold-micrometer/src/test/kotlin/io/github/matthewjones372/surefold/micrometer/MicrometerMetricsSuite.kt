package io.github.matthewjones372.surefold.micrometer

import io.github.matthewjones372.surefold.Reading
import io.github.matthewjones372.surefold.counter
import io.github.matthewjones372.surefold.gauge
import io.github.matthewjones372.surefold.histogram
import io.github.matthewjones372.surefold.increment
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeInstanceOf
import io.github.matthewjones372.surefold.junit.withClue
import io.github.matthewjones372.surefold.metricTagged
import io.github.matthewjones372.surefold.metrics
import io.github.matthewjones372.surefold.parMap
import io.github.matthewjones372.surefold.readings
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.FutureTask

private fun <A> recording(registry: SimpleMeterRegistry, block: () -> A): A =
    metrics.locally(MicrometerMetrics(registry), block)

/** What [read] answers on a new thread, where nothing is bound: a suite's test runs under a capture of its own. */
private fun <A> unbound(read: () -> A): A = FutureTask(read).also { Thread.ofVirtual().start(it) }.get()

/** What a measurement looks like once it reaches a registry, asserted against a real one. */
object MicrometerMetricsSuite : Suite() {
    init {
        test("a counter arrives with its name and its total") {
            val registry = SimpleMeterRegistry()

            recording(registry) {
                counter("petshop.adoptions").increment()
                counter("petshop.adoptions").increment(by = 2.0)
            }

            registry.counter("petshop.adoptions").count() shouldBe 3.0
        }

        test("a tag in scope is a tag on the meter") {
            val registry = SimpleMeterRegistry()

            recording(registry) {
                metricTagged("species" to "tortoise") { counter("petshop.adoptions").increment() }
            }

            withClue("one series per species, which is what a tag is for") {
                registry.counter("petshop.adoptions", "species", "tortoise").count() shouldBe 1.0
            }
        }

        test("a gauge reads back what it was set to, through a registry that pulls") {
            val registry = SimpleMeterRegistry()

            recording(registry) {
                gauge("petshop.queue.depth").set(4.0)
                gauge("petshop.queue.depth").set(2.0)
            }

            withClue("Micrometer asks a gauge for its value; this holds a number for it to ask") {
                registry.get("petshop.queue.depth").gauge().value() shouldBe 2.0
            }
        }

        test("a histogram arrives as a distribution") {
            val registry = SimpleMeterRegistry()

            recording(registry) {
                histogram("petshop.adopt.ms").record(12.0)
                histogram("petshop.adopt.ms").record(30.0)
            }

            registry.get("petshop.adopt.ms").summary().let { taken ->
                (taken.count() shouldBe 2L) and (taken.totalAmount() shouldBe 42.0)
            }
        }

        test("a fork measures into the same meter as its opener") {
            val registry = SimpleMeterRegistry()

            recording(registry) {
                metricTagged("species" to "tortoise") {
                    val _ = parMap(listOf(1, 2, 3)) { counter("petshop.adoptions").increment() }
                }
            }

            registry.counter("petshop.adoptions", "species", "tortoise").count() shouldBe 3.0
        }

        test("nothing is bound in main and the classpath still answers") {
            withClue("the service file is the whole of the setup, so it is asserted not assumed") {
                unbound { metrics.get() }.shouldBeInstanceOf<MicrometerMetrics>()
            }
        }
    }
}

/** Spec 0109: what a registry holds, read back through surefold, one reading for each set of tags. */
object MicrometerReadingsSuite : Suite() {
    init {
        test(
            "counters, gauges and bucketed histograms read back from the registry, each tag set its own",
        ) {
            val registry = SimpleMeterRegistry()
            registry.config().meterFilter(
                object : MeterFilter {
                    override fun configure(
                        id: Meter.Id,
                        config: DistributionStatisticConfig,
                    ) = DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(1.0, 10.0).build().merge(config)
                },
            )

            metrics.locally(MicrometerMetrics(registry)) {
                counter("petshop.adoptions", "species" to "tortoise").increment(by = 2.0)
                counter("petshop.adoptions", "species" to "hare").increment()
                gauge("petshop.kennels").set(12.0)
                histogram("petshop.wait").record(0.5)
                histogram("petshop.wait").record(5.0)

                val totals = readings("petshop.adoptions").toSet() shouldBe setOf(
                    Reading.Total("petshop.adoptions", mapOf("species" to "tortoise"), 2.0),
                    Reading.Total("petshop.adoptions", mapOf("species" to "hare"), 1.0),
                )
                val values = readings("petshop.kennels") shouldBe
                    listOf(Reading.Value("petshop.kennels", emptyMap(), 12.0))
                val distributions = readings("petshop.wait") shouldBe listOf(
                    Reading.Distribution(
                        "petshop.wait", emptyMap(), 2, 5.5, mapOf(1.0 to 1.0, 10.0 to 2.0),
                    ),
                )
                totals and values and distributions
            }
        }
    }
}
