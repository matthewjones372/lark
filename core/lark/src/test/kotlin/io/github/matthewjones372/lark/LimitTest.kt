package io.github.matthewjones372.lark

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A limit on calls into one dependency: a fixed one never lets more in, and an adaptive one finds its level. */
class LimitTest {

    /**
     * A dependency that serves [capacity] calls at once in [baseNanos]; beyond that, calls queue, and each takes
     * longer in proportion. Drives [vegas] with one call at the current limit per step, for [steps] steps.
     */
    private fun settle(vegas: Vegas, start: Int, capacity: () -> Int, steps: Int): Int {
        var limit = start
        repeat(steps) {
            val took = (BASE_NANOS * maxOf(1.0, limit.toDouble() / capacity())).toLong()
            limit = vegas.sample(took, inFlight = limit, dropped = false)
        }
        return limit
    }

    @Test
    fun `an adaptive limit climbs to what the dependency can take, and stops a little above it`() {
        val limit = settle(Vegas(initial = 20, min = 1, max = 5_000), 20, capacity = { 400 }, steps = 50_000)

        withClue("settled at $limit for a capacity of 400") { (limit in 400..440) shouldBe true }
    }

    @Test
    fun `an adaptive limit follows the dependency down within a few windows when its capacity halves`() {
        val vegas = Vegas(initial = 20, min = 1, max = 5_000)
        var capacity = 400
        val before = settle(vegas, 20, { capacity }, steps = 50_000)
        capacity = 200

        val limit = settle(vegas, before, { capacity }, steps = 2_000)

        withClue("settled at $limit for a capacity of 200") { (limit in 200..230) shouldBe true }
    }

    @Test
    fun `an adaptive limit that is barely used does not grow`() {
        val vegas = Vegas(initial = 20, min = 1, max = 5_000)
        var limit = 20
        repeat(1_000) { limit = vegas.sample(BASE_NANOS, inFlight = 2, dropped = false) }

        limit shouldBe 20
    }

    @Test
    fun `a failed call backs the limit off at once`() {
        val vegas = Vegas(initial = 100, min = 1, max = 1_000)

        vegas.sample(BASE_NANOS, inFlight = 100, dropped = true) shouldBe 90
    }

    @Test
    fun `an adaptive limit never leaves its bounds`() {
        val vegas = Vegas(initial = 10, min = 5, max = 50)

        settle(vegas, 10, capacity = { 1_000 }, steps = 5_000) shouldBe 50
        repeat(100) { vegas.sample(BASE_NANOS, inFlight = 50, dropped = true) }
        vegas.sample(BASE_NANOS, inFlight = 5, dropped = true) shouldBe 5
    }

    @Test
    fun `a fixed limit never lets more than its number in at once`() {
        val limit = Limit.fixed(4)
        val now = AtomicInteger()
        val most = AtomicInteger()
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            repeat(200) {
                threads.submit {
                    limit.withPermit {
                        most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                        Thread.sleep(1)
                        now.decrementAndGet()
                    }
                }
            }
        }

        withClue("the most calls ever inside at once") { (most.get() <= 4) shouldBe true }
        limit.inFlight shouldBe 0
    }

    @Test
    fun `a call waits for room, and gets it when another leaves`() {
        val limit = Limit.fixed(1)
        val inside = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val first = Thread.ofVirtual().start { limit.withPermit { inside.countDown(); leave.await() } }
        inside.await()
        val second = Thread.ofVirtual().start { limit.withPermit { } }

        withClue("the second call is still waiting") { second.join(Duration.ofMillis(WAIT_MILLIS)) shouldBe false }
        leave.countDown()

        first.join(Duration.ofSeconds(SETTLE_SECONDS)) shouldBe true
        second.join(Duration.ofSeconds(SETTLE_SECONDS)) shouldBe true
    }

    @Test
    fun `a call that fails backs an adaptive limit off, and one that is cancelled does not`() {
        val limit = Limit.adaptive("db", initial = 100, max = 1_000)

        try {
            limit.withPermit { error("connection reset") }
        } catch (_: IllegalStateException) {
            // The failure the limit learns from.
        }
        limit.current shouldBe 90

        try {
            limit.withPermit { throw CancellationException("raised") }
        } catch (_: CancellationException) {
            // A declared failure, which says nothing about the dependency.
        }
        limit.current shouldBe 90
        limit.inFlight shouldBe 0
    }

    @Test
    fun `an adaptive limit publishes what it allows and what is inside it`() {
        capturingMetrics { captured ->
            Limit.adaptive("orders-db", initial = 10, max = 100).withPermit { }

            captured.gauge("lark.limit.current") shouldBe 10.0
            captured.gauge("lark.limit.in_use") shouldBe 0.0
        }
    }

    private companion object {
        const val BASE_NANOS = 10_000_000L
        const val SETTLE_SECONDS = 10L
        const val WAIT_MILLIS = 50L
    }
}
