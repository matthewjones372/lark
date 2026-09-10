package io.github.matthewjones372.lark

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

class TestClockTest {

    private fun sleeping(clock: TestClock) {
        while (clock.sleepers() == 0) Thread.onSpinWait()
    }

    @Test
    fun `a test clock only moves when a test moves it`() {
        val moving = TestClock(Instant.EPOCH)

        moving.now() shouldBe Instant.EPOCH
        moving.adjust(60.minutes)

        moving.now() shouldBe Instant.EPOCH.plusSeconds(60 * 60)
    }

    @Test
    fun `a sleep does not return until the clock is moved past it`() {
        val moving = TestClock()
        val woke = AtomicBoolean()
        val sleeper = Thread { clock.locally(moving) { clock.get().sleep(60.minutes); woke.set(true) } }

        sleeper.start()
        sleeping(moving)

        withClue("the clock has not moved, so neither has the sleeper") { woke.get() shouldBe false }

        moving.adjust(60.minutes)
        sleeper.join(5_000)

        woke.get() shouldBe true
    }

    @Test
    fun `nothing has happened before the recurrence, and one thing has after each`() {
        val moving = TestClock()
        val ticks = AtomicInteger()

        val ran = clock.locally(moving) {
            flock<Nothing, Int> {
                val poller = async {
                    repeat(2) { clock.get().sleep(60.minutes); ticks.incrementAndGet() }
                    ticks.get()
                }
                moving.adjustWhenBlocked(60.minutes)
                // Returns only once something is asleep again, which the first tick had to precede.
                moving.adjustWhenBlocked(60.minutes)
                poller.await()
            }
        }

        ran.getOrNull().shouldNotBeNull() shouldBe 2
    }

    @Test
    fun `setting the time releases every sleep it passes`() {
        val moving = TestClock()
        val woke = AtomicInteger()
        val sleepers = (1..3).map {
            Thread { clock.locally(moving) { clock.get().sleep(it.minutes); woke.incrementAndGet() } }
        }

        sleepers.forEach(Thread::start)
        while (moving.sleepers() < 3) Thread.onSpinWait()
        moving.setTime(Instant.EPOCH.plusSeconds(10 * 60))
        sleepers.forEach { it.join(5_000) }

        woke.get() shouldBe 3
    }

    @Test
    fun `a sleep of nothing does not wait to be released`() {
        val moving = TestClock()

        clock.locally(moving) { clock.get().sleep(kotlin.time.Duration.ZERO) }

        moving.sleepers() shouldBe 0
    }
}
