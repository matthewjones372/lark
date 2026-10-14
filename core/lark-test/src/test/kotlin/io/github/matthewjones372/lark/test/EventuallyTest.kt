package io.github.matthewjones372.lark.test

import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EventuallyTest {

    @Test
    fun `eventually answers the first try that does not throw`() {
        val tries = AtomicInteger(0)

        val answer = eventually(1.seconds, every = 1.milliseconds) {
            check(tries.incrementAndGet() >= 3) { "not yet" }
            "there"
        }

        answer shouldBe "there"
        tries.get() shouldBe 3
    }

    @Test
    fun `a failing eventually gives up on time, naming the last failure, the tries and the time taken`() {
        val moving = TestClock()
        val last = AssertionError("expected:<0L> but was:<3L>")

        val gaveUp = clock.locally(moving) {
            flock<Nothing, GaveUp> {
                val waiting = async { shouldThrow<GaveUp> { eventually(5.seconds, every = 1.seconds) { throw last } } }
                repeat(5) { moving.adjustWhenBlocked(1.seconds) }
                waiting.await()
            }
        }.getOrNull()!!

        gaveUp.cause shouldBeSameInstanceAs last
        withClue("a try at each second from 0 to 5, and the one at 5 finds five seconds gone") {
            gaveUp.tries shouldBe 6L
            gaveUp.elapsed shouldBe 5.seconds
        }
        gaveUp.message!! shouldContain "6 tries"
        gaveUp.message!! shouldContain "5s"
        gaveUp.message!! shouldContain "expected:<0L> but was:<3L>"
    }

    @Test
    fun `an interrupt is not a failure to try again`() {
        shouldThrow<InterruptedException> {
            eventually(5.seconds) { throw InterruptedException("stop") }
        }
    }
}
