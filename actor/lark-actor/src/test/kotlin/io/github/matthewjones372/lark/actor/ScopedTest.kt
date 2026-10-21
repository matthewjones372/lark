package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private sealed interface Bulb

private data class Lit(val brightness: Int) : Bulb

private data object Off : Bulb

private sealed interface Switch

/** Lights the bulb, with a fade and a shimmer that belong to being lit. */
private data object On : Switch

/** A copy of the same state. */
private data object Brighter : Switch

/** A state of another class. */
private data object Flip : Switch

/** Asks for a state with timers, then stays instead. */
private data object Stray : Switch

private data object Fade : Switch

private data object Shimmer : Switch

private fun lamp(log: AtomicReference<List<String>>) = behaviour<Switch, Bulb>(Off) { ctx, bulb, message ->
    when (message) {
        On -> ctx.become(Lit(1)) {
            after(5.seconds, Fade)
            every(2.seconds, Shimmer)
        }

        Brighter -> if (bulb is Lit) become(bulb.copy(brightness = bulb.brightness + 1)) else unhandled()

        Flip -> become(Off)

        Stray -> {
            ctx.become(Lit(9)) { after(1.seconds, Fade) }
            stay()
        }

        Fade -> {
            log.updateAndGet { it + "fade" }
            stay()
        }

        Shimmer -> {
            log.updateAndGet { it + "shimmer" }
            stay()
        }
    }
}

class ScopedTest {

    private val log = AtomicReference<List<String>>(emptyList())

    @Test
    fun `the timers a state starts with arrive while it lasts`() {
        val lamp = lamp(log).test()

        lamp.send(On)
        lamp.advance(5.seconds)

        log.get() shouldContainExactly listOf("shimmer", "shimmer", "fade")
    }

    @Test
    fun `becoming a state of another class cancels its timers`() {
        val lamp = lamp(log).test()
        lamp.send(On)
        lamp.advance(3.seconds)

        lamp.send(Flip)
        lamp.advance(1.hours)

        log.get() shouldContainExactly listOf("shimmer")
        lamp.pendingTimers shouldBe 0
    }

    @Test
    fun `a copy of the same state keeps them`() {
        val lamp = lamp(log).test()
        lamp.send(On)

        lamp.send(Brighter)
        lamp.advance(5.seconds)

        lamp.state shouldBe Lit(2)
        log.get() shouldContainExactly listOf("shimmer", "shimmer", "fade")
    }

    @Test
    fun `becoming the state again with timers replaces the ones it had`() {
        val lamp = lamp(log).test()
        lamp.send(On)
        lamp.advance(3.seconds)

        lamp.send(On)
        lamp.advance(5.seconds)

        withClue("the first fade, due at five, went with the timers it was replaced by") {
            log.get() shouldContainExactly listOf("shimmer", "shimmer", "shimmer", "fade")
        }
    }

    @Test
    fun `the timers of a become the step does not return never start`() {
        val lamp = lamp(log).test()

        lamp.send(Stray)
        lamp.advance(1.hours)

        lamp.state shouldBe Off
        log.get().shouldBeEmpty()
        lamp.pendingTimers shouldBe 0
    }

    @Test
    fun `on threads, a state's timers last while it does and a copy keeps them`() {
        val moving = TestClock()
        clock.locally(moving) {
            flock<Nothing, Unit> {
                val lamp = spawn("lamp", lamp(log))
                lamp.tell(On)
                lamp.tell(Brighter)
                awaitIdle()
                moving.adjust(5.seconds)

                lamp.tell(Flip)
                awaitIdle()
                moving.adjust(1.hours)
            }
        }

        log.get() shouldContainExactly listOf("shimmer", "shimmer", "fade")
    }
}
