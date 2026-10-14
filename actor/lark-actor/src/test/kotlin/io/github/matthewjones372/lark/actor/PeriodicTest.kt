package io.github.matthewjones372.lark.actor

import arrow.core.right
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private sealed interface Pulse

private data class Beat(val every: Duration, val times: Int = Int.MAX_VALUE) : Pulse

/** Replaces the beat with one that sounds once. */
private data object Once : Pulse

private data class Quiet(val after: Duration?) : Pulse

private data object Poke : Pulse

private data object Tick : Pulse

private data object Hush : Pulse

private data class Listen(val reply: Reply<List<String>>) : Pulse

private data object Stumble : Pulse

private data class Heart(val heard: List<String> = emptyList(), val times: Int = Int.MAX_VALUE)

/** Beats on a timer, falls quiet on a receive timeout, and writes down what it heard. */
private fun heart() = behaviour<Pulse, Heart>(Heart()) { ctx, heart, message ->
    when (message) {
        is Beat -> {
            ctx.timers.every("beat", message.every, Tick)
            become(heart.copy(times = message.times))
        }

        Once -> {
            ctx.timers.after("beat", 1.seconds, Tick)
            stay()
        }

        is Quiet -> {
            val after = message.after
            if (after == null) ctx.receiveTimeout(null) else ctx.receiveTimeout(after, Hush)
            stay()
        }

        Poke -> become(heart.copy(heard = heart.heard + "poke"))

        Tick -> {
            val heard = heart.heard + "tick"
            if (heard.count { it == "tick" } >= heart.times) ctx.timers.cancel("beat")
            become(heart.copy(heard = heard))
        }

        Hush -> become(heart.copy(heard = heart.heard + "hush"))

        is Listen -> {
            message.reply(heart.heard)
            stay()
        }

        Stumble -> error("the heart stumbled")
    }
}

class PeriodicTest {

    @Test
    fun `a periodic timer sounds once each interval until it is cancelled`() {
        val heart = heart().test()
        heart.send(Beat(every = 1.seconds, times = 3))

        heart.advance(1.hours)

        heart.state.heard shouldContainExactly listOf("tick", "tick", "tick")
        heart.pendingTimers shouldBe 0
    }

    @Test
    fun `a periodic timer runs on in time order with everything else`() {
        val heart = heart().test()
        heart.send(Beat(every = 2.seconds))

        heart.advance(5.seconds)

        heart.state.heard shouldContainExactly listOf("tick", "tick")
        heart.pendingTimers shouldBe 1
    }

    @Test
    fun `starting its key again replaces a periodic timer`() {
        val heart = heart().test()
        heart.send(Beat(every = 1.seconds))

        heart.send(Once)
        heart.advance(1.hours)

        heart.state.heard shouldContainExactly listOf("tick")
    }

    @Test
    fun `an idle actor hears its receive timeout once per silence`() {
        val heart = heart().test()
        heart.send(Quiet(5.seconds))

        heart.advance(4.seconds)
        withClue("still inside the silence") { heart.state.heard.shouldBeEmpty() }
        heart.advance(1.hours)

        heart.state.heard shouldContainExactly listOf("hush")
    }

    @Test
    fun `any message resets the receive timeout`() {
        val heart = heart().test()
        heart.send(Quiet(5.seconds))

        heart.advance(4.seconds)
        heart.send(Poke)
        heart.advance(4.seconds)
        heart.send(Poke)
        heart.advance(4.seconds)
        withClue("never five seconds without a message") {
            heart.state.heard shouldContainExactly listOf("poke", "poke")
        }
        heart.advance(1.seconds)

        heart.state.heard shouldContainExactly listOf("poke", "poke", "hush")
    }

    @Test
    fun `a message after the timeout starts the next silence`() {
        val heart = heart().test()
        heart.send(Quiet(5.seconds))
        heart.advance(5.seconds)

        heart.send(Poke)
        heart.advance(5.seconds)

        heart.state.heard shouldContainExactly listOf("hush", "poke", "hush")
    }

    @Test
    fun `null turns the receive timeout off`() {
        val heart = heart().test()
        heart.send(Quiet(5.seconds))

        heart.send(Quiet(null))
        heart.advance(1.hours)

        heart.state.heard.shouldBeEmpty()
        heart.pendingTimers shouldBe 0
    }

    @Test
    fun `a restart turns the receive timeout off, and cancels a periodic timer`() {
        val heart = heart().test(restart = Schedule.recurs(1))
        heart.send(Quiet(5.seconds))
        heart.send(Beat(every = 1.seconds))

        heart.send(Stumble)
        heart.advance(1.hours)

        heart.state.heard.shouldBeEmpty()
        heart.pendingTimers shouldBe 0
    }

    @Test
    fun `on threads, the flock's clock drives a periodic timer and a receive timeout`() {
        val moving = TestClock()
        val heard = clock.locally(moving) {
            flock<Nothing, Any> {
                val heart = spawn("heart", heart())
                heart.tell(Beat(every = 2.seconds, times = 2))
                heart.tell(Quiet(10.seconds))
                awaitIdle()

                moving.adjust(1.hours)
                heart.ask(1.minutes) { Listen(it) }
            }
        }

        heard shouldBe listOf("tick", "tick", "hush").right().right()
    }
}
