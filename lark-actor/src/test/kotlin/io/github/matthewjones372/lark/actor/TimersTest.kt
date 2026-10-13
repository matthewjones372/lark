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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private sealed interface Alarm

private data class Arm(val key: String, val delay: Duration, val note: String) : Alarm

private data class Disarm(val key: String) : Alarm

/** Starts a timer and cancels it in the same step, so its message is already in the mailbox when it is cancelled. */
private data class Flicker(val key: String) : Alarm

/** Starts a key twice in one step, so the first message is already in the mailbox when it is replaced. */
private data class Swap(val key: String) : Alarm

private data class Ring(val note: String) : Alarm

private data class Heard(val reply: Reply<List<String>>) : Alarm

/** Answers once [count] notes have rung, so a test on the wall clock waits on the ring rather than on time. */
private data class Rung(val count: Int, val reply: Reply<List<String>>) : Alarm

private data object Fuse : Alarm

private data class Bell(val rung: List<String> = emptyList(), val waiting: Rung? = null)

/** Rings what its timers send, and answers what has rung. */
private fun alarm() = behaviour<Alarm, Bell>(Bell()) { ctx, bell, message ->
    when (message) {
        is Arm -> {
            ctx.timers.after(message.key, message.delay, Ring(message.note))
            stay()
        }

        is Disarm -> {
            ctx.timers.cancel(message.key)
            stay()
        }

        is Flicker -> {
            ctx.timers.after(message.key, Duration.ZERO, Ring("flicker"))
            ctx.timers.cancel(message.key)
            stay()
        }

        is Swap -> {
            ctx.timers.after(message.key, Duration.ZERO, Ring("first"))
            ctx.timers.after(message.key, Duration.ZERO, Ring("second"))
            stay()
        }

        is Ring -> {
            val rung = bell.rung + message.note
            val waiting = bell.waiting?.takeUnless { rung.size >= it.count }
            bell.waiting?.takeIf { waiting == null }?.reply?.invoke(rung)
            become(Bell(rung, waiting))
        }

        is Heard -> {
            message.reply(bell.rung)
            stay()
        }

        is Rung -> if (bell.rung.size >= message.count) {
            message.reply(bell.rung)
            stay()
        } else {
            become(bell.copy(waiting = message))
        }

        Fuse -> error("the alarm blew a fuse")
    }
}

private fun ActorRef<Alarm>.heard() = ask(1.minutes) { Heard(it) }

class TimersTest {

    @Test
    fun `a timer's message arrives once its delay has passed, and not before`() {
        val alarm = alarm().test()
        alarm.send(Arm("wake", 5.seconds, "wake"))

        alarm.advance(4.seconds)

        withClue("a second early") { alarm.state.rung.shouldBeEmpty() }
        alarm.pendingTimers shouldBe 1

        alarm.advance(1.seconds)

        alarm.state.rung shouldContainExactly listOf("wake")
        alarm.pendingTimers shouldBe 0
    }

    @Test
    fun `what falls due is delivered in time order, however it was started`() {
        val alarm = alarm().test()
        alarm.send(Arm("late", 3.seconds, "late"))
        alarm.send(Arm("early", 1.seconds, "early"))
        alarm.send(Arm("middle", 2.seconds, "middle"))

        alarm.advance(1.hours)

        alarm.state.rung shouldContainExactly listOf("early", "middle", "late")
    }

    @Test
    fun `starting a key that is running replaces it`() {
        val alarm = alarm().test()
        alarm.send(Arm("wake", 5.seconds, "early"))
        alarm.send(Arm("wake", 10.seconds, "late"))

        alarm.advance(1.hours)

        alarm.state.rung shouldContainExactly listOf("late")
    }

    @Test
    fun `a cancelled timer's message never arrives`() {
        val alarm = alarm().test()
        alarm.send(Arm("wake", 5.seconds, "wake"))
        alarm.send(Disarm("wake"))

        alarm.advance(1.hours)

        alarm.state.rung.shouldBeEmpty()
        alarm.pendingTimers shouldBe 0
    }

    @Test
    fun `a cancelled or replaced timer's message never arrives, even one already in the mailbox`() {
        val alarm = alarm().test()

        alarm.send(Flicker("now"))
        alarm.send(Swap("now"))

        alarm.state.rung shouldContainExactly listOf("second")
    }

    @Test
    fun `a stop cancels every timer`() {
        testActors {
            val alarm = spawn("alarm", alarm())
            alarm.send(Arm("wake", 5.seconds, "wake"))

            alarm.halt()

            pendingTimers shouldBe 0
        }
    }

    @Test
    fun `a restart cancels every timer`() {
        val alarm = alarm().test(restart = Schedule.recurs(1))
        alarm.send(Arm("wake", 5.seconds, "wake"))

        alarm.send(Fuse)
        alarm.advance(1.hours)

        alarm.restarts shouldBe 1
        alarm.state.rung.shouldBeEmpty()
    }

    @Test
    fun `on threads, a timer falls due when the flock's clock moves, and a cancelled one never does`() {
        val moving = TestClock()
        val heard = clock.locally(moving) {
            flock<Nothing, Any> {
                val alarm = spawn("alarm", alarm())
                alarm.tell(Arm("wake", 5.seconds, "wake"))
                alarm.tell(Arm("snooze", 6.seconds, "snooze"))
                alarm.tell(Disarm("snooze"))
                awaitIdle()

                moving.adjust(4.seconds)
                val early = alarm.heard()
                moving.adjust(1.hours)
                early to alarm.heard()
            }
        }

        heard shouldBe (emptyList<String>().right() to listOf("wake").right()).right()
    }

    @Test
    fun `on threads, a cancelled or replaced timer's message never arrives, even one already in the mailbox`() {
        val heard = flock<Nothing, Any> {
            val alarm = spawn("alarm", alarm())
            alarm.tell(Flicker("now"))
            alarm.tell(Swap("now"))
            awaitIdle()
            alarm.heard()
        }

        heard shouldBe listOf("second").right().right()
    }

    @Test
    fun `on the wall clock, a sooner timer is not held behind a later one`() {
        val heard = flock<Nothing, Any> {
            val alarm = spawn("alarm", alarm())
            alarm.tell(Arm("later", 1.hours, "later"))
            alarm.tell(Arm("sooner", 1.milliseconds, "sooner"))
            alarm.ask(1.minutes) { Rung(1, it) }
        }

        heard shouldBe listOf("sooner").right().right()
    }
}
