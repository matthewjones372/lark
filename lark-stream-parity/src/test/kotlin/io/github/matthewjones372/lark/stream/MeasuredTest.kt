package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.Histogram
import io.github.matthewjones372.lark.Metrics
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.pekko.actor.ActorSystem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.DoubleAdder

/** A measured run on every backend: each stage's elements counted, and the slow one's body the busiest. */
class MeasuredTest {

    companion object {
        private val system: ActorSystem = ActorSystem.create("lark-stream-measured-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
            system.getWhenTerminated().toCompletableFuture().join()
        }

        private const val SLOW_MILLIS = 2L
        private const val SETTLE_SECONDS = 10L
    }

    private val backends: List<StreamBackend> = listOf(PekkoStreams(system), Forks())

    /** Every instrument, kept by stage and step so a test can read them back. */
    private class Recorded : Metrics {
        val counts = ConcurrentHashMap<String, DoubleAdder>()
        val samples = ConcurrentHashMap<String, ConcurrentLinkedQueue<Double>>()

        private fun key(tags: Map<String, String>) = "${tags["step"]}:${tags["stage"]}"

        override fun counter(name: String, tags: Map<String, String>): Counter {
            val total = counts.computeIfAbsent(key(tags)) { DoubleAdder() }
            return Counter { by -> total.add(by) }
        }

        override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { }

        override fun histogram(name: String, tags: Map<String, String>): Histogram {
            val metric = name.substringAfterLast('.')
            val kept = samples.computeIfAbsent("$metric|${key(tags)}") { ConcurrentLinkedQueue() }
            return Histogram { value -> kept.add(value) }
        }

        /** The mean of one histogram, by metric (`busy` or `waiting`) and `step:stage`. */
        fun mean(metric: String, stage: String): Double = samples.getValue("$metric|$stage").average()

        fun stages(metric: String): Set<String> =
            samples.keys.filter { it.startsWith("$metric|") }.map { it.substringAfter('|') }.toSet()

        fun busiest(): String = stages("busy").maxBy { mean("busy", it) }
    }

    private val pipeline: Run<Nothing, List<Int>> =
        Stream.from(1..20)
            .map { it + 1 }
            .map { n ->
                Thread.sleep(SLOW_MILLIS)
                n
            }
            .filter { it % 2 == 0 }
            .take(100)
            .runCollect()

    @TestFactory
    fun `the slow stage is the busiest, and every stage counts what it emitted`(): List<DynamicTest> =
        backends.map { backend ->
            dynamicTest(backend.key.name) {
                val recorded = Recorded()
                val measured = pipeline.measured(Measured("orders", recorded, sampleEvery = 1))

                val exit = measured.run(backend).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit.shouldBeInstanceOf<Exit.Done<List<Int>>>().value.size shouldBe 10
                withClue("the map that sleeps is step 2, and it is where the time went") {
                    recorded.busiest() shouldBe "2:map"
                    recorded.mean("busy", "2:map") shouldBeGreaterThan SLOW_MILLIS * 0.9
                }
                withClue("a stage with no body of its own to time reports no busy, not an empty series") {
                    recorded.stages("busy") shouldBe setOf("1:map", "2:map", "3:filter")
                }
                recorded.counts.mapValues { it.value.sum().toInt() } shouldBe mapOf(
                    "0:Stream.from" to 20,
                    "1:map" to 20,
                    "2:map" to 20,
                    "3:filter" to 10,
                    "4:take" to 10,
                )
            }
        }

    private val slowInItsOwnStage: Run<Nothing, List<Int>> =
        Stream.from(1..20)
            .map { it + 1 }
            .map { it * 1 }
            .mapConcat { n ->
                Thread.sleep(SLOW_MILLIS)
                listOf(n)
            }
            .drop(0)
            .runCollect()

    @TestFactory
    fun `stages upstream of the slow one wait for demand, and waiting drops at the slow one`(): List<DynamicTest> =
        backends.map { backend ->
            dynamicTest(backend.key.name) {
                val recorded = Recorded()
                val measured = slowInItsOwnStage.measured(Measured("orders", recorded, sampleEvery = 1))

                measured.run(backend).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
                    .shouldBeInstanceOf<Exit.Done<List<Int>>>()

                // The source, the fused run of maps ending at step 2, the slow mapConcat, and the drop after it.
                recorded.stages("waiting") shouldBe setOf("0:Stream.from", "2:map", "3:mapConcat", "4:drop")
                val upstream = listOf("0:Stream.from", "2:map").map { recorded.mean("waiting", it) }
                val atAndAfter = listOf("3:mapConcat", "4:drop").map { recorded.mean("waiting", it) }
                withClue("waiting upstream $upstream, at and after the slow stage $atAndAfter") {
                    upstream.min() shouldBeGreaterThan SLOW_MILLIS * 0.9
                    upstream.min() shouldBeGreaterThan atAndAfter.max() * 10
                }
            }
        }

    @Test
    fun `a measured run fuses as an unmeasured one does, and each step keeps its own numbers`() {
        val compiled = pipeline.measured(Measured("orders", Recorded())).node.optimised()

        val fused = compiled.shouldBeInstanceOf<Node.Probed>().upstream.shouldBeInstanceOf<Node.Take>()
            .upstream.shouldBeInstanceOf<Node.Probed>().upstream.shouldBeInstanceOf<Node.Fused>()
        fused.steps.map { it.operator } shouldContainExactly listOf("map", "map", "filter")
    }
}
