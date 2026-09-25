package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Every parity case on Forks, with every thread it starts counted: run to the end, stopped as soon as it
 * starts, and stopped part way through. However a run ends, nothing it started is still running once its
 * exit has completed. Then the whole suite again, many times, to shake out what only shows up sometimes.
 */
class HardenedTest {

    private val parity = ParityTest()

    private val soak: Int = System.getProperty("lark.stream.soak")?.toIntOrNull() ?: DEFAULT_SOAK

    private fun Counted.leavesNothing(case: ParityTest.Case, how: String) =
        withClue("${case.name}, $how: a thread it started is still running") { leftRunning() shouldBe 0 }

    @TestFactory
    fun `every case run to the end leaves nothing running`(): List<DynamicTest> =
        parity.cases().map { case ->
            dynamicTest(case.name) {
                val counted = Counted()
                parity.check(case, Forks(counted))
                counted.leavesNothing(case, "run to the end")
            }
        }

    @TestFactory
    fun `every case stopped as soon as it starts ends, and leaves nothing running`(): List<DynamicTest> =
        parity.cases().map { case ->
            dynamicTest(case.name) {
                val counted = Counted()
                val running = case.run().start(Forks(counted))
                running.stop()
                running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
                counted.leavesNothing(case, "stopped at once")
            }
        }

    @TestFactory
    fun `every case stopped part way through ends, and leaves nothing running`(): List<DynamicTest> =
        parity.cases().map { case ->
            dynamicTest(case.name) {
                val counted = Counted()
                val running = case.run().start(Forks(counted))
                Thread.sleep(Random.nextLong(0, 3))
                running.stop()
                running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
                counted.leavesNothing(case, "stopped part way")
            }
        }

    @Test
    fun `the whole suite, many times over, answers the same and leaves nothing running`() {
        val counted = Counted()
        repeat(soak) {
            parity.cases().forEach { case -> parity.check(case, Forks(counted)) }
        }
        withClue("after $soak runs of every case") { counted.leftRunning() shouldBe 0 }
    }

    @Test
    fun `a run stopped while a slow body is in flight ends at once, Done with what it had`() {
        val counted = Counted()
        val bodies = AtomicInteger()
        val running = Stream.from(1..100)
            .mapPar(4) { n ->
                bodies.incrementAndGet()
                if (n > 2) Thread.sleep(60_000)
                n
            }
            .runCollect()
            .start(Forks(counted))
        // The window refills before it waits, so a sixth body started means 1 and 2 have been taken.
        while (bodies.get() < 6) Thread.sleep(1)

        running.stop()

        running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
            .shouldBeInstanceOf<Exit.Done<List<Int>>>().value shouldBe listOf(1, 2)
        counted.leftRunning() shouldBe 0
    }

    @Test
    fun `a run stopped while it waits on an empty buffer ends at once`() {
        val counted = Counted()
        val running = Stream.from(generateSequence(1) { it + 1 }.asIterable())
            .map { n ->
                if (n > 1) Thread.sleep(60_000)
                n
            }
            .buffer(2)
            .runCollect()
            .start(Forks(counted))
        Thread.sleep(50)

        running.stop()

        running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(listOf(1))
        counted.leftRunning() shouldBe 0
    }

    @Test
    fun `the count sees a task left running, so a leak would turn these red`() {
        val counted = Counted()
        counted.execute { Thread.sleep(5_000) }

        counted.leftRunning(within = kotlin.time.Duration.parse("100ms")) shouldBe 1
    }

    private companion object {
        const val SETTLE_SECONDS = 10L
        const val DEFAULT_SOAK = 200
    }
}
