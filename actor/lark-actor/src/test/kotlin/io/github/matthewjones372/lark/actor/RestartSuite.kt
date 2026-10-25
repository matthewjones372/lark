package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.resource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private data object Jammed

private sealed interface Press

private data class Stamp(val n: Int) : Press

private data object Jam : Press

private data object Crack : Press

private data class Stamped(val reply: Reply<List<Int>>) : Press

/** Collects stamps; a `Jam` raises and a `Crack` throws, and either loses what was collected. */
private fun press() = behaviour<Press, List<Int>, Jammed>(emptyList()) { _, stamped, message ->
    when (message) {
        is Stamp -> become(stamped + message.n)

        Jam -> fail(Jammed)

        Crack -> error("the press cracked")

        is Stamped -> {
            message.reply(stamped)
            stay()
        }
    }
}

object RestartSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a raise restarts the actor from its initial state, and it carries on") { _ ->
            val press = press().test(restart = Schedule.recurs(3))

            press.send(Stamp(1))
            press.send(Jam)
            press.send(Stamp(2))

            (press.stopped shouldBe false) and (press.state shouldBe listOf(2)) and (press.restarts shouldBe 1)
        }

        test("a throw restarts it on the same schedule, and does not reach the test") { _ ->
            val press = press().test(restart = Schedule.recurs(3))

            press.send(Crack)

            (press.stopped shouldBe false) and (press.restarts shouldBe 1)
        }

        test("when the schedule is done the actor stops, with the failure that ended it") { _ ->
            val press = press().test(restart = Schedule.recurs(1))

            press.send(Jam)
            press.send(Jam)

            (press.stopped shouldBe true) and
                (press.restarts shouldBe 1) and
                (press.failure shouldBe Failure.Declared(Jammed))
        }

        test("the test kit records each restart's delay rather than waiting it out") { _ ->
            val press = press().test(restart = Schedule.exponential(100.milliseconds))

            repeat(3) { press.send(Jam) }

            press.delays shouldBe listOf(100.milliseconds, 200.milliseconds, 400.milliseconds)
        }

        test("with no schedule a throw still stops the actor and reaches the test") { _ ->
            val press = press().test()

            val thrown = shouldThrow<IllegalStateException> { press.send(Crack) }

            thrown and (press.stopped shouldBe true)
        }

        test("on threads, a restart waits on the flock's clock and the mailbox is intact") { _ ->
            val time = TestClock()
            val stamped = clock.locally(time) {
                flock<Nothing, Any> {
                    val press = spawn("press", press(), restart = Schedule.spaced(1.hours))
                    press.tell(Stamp(1))
                    press.tell(Jam)
                    press.tell(Stamp(2))
                    press.tell(Stamp(3))
                    time.adjustWhenBlocked(1.hours)
                    press.ask(1.minutes) { Stamped(it) }
                }
            }

            stamped shouldBeRight listOf(2, 3).right()
        }

        test("on threads, a schedule that is done stops the actor") { _ ->
            val answer = flock<Nothing, Any> {
                val press = spawn("press", press(), restart = Schedule.recurs(0))
                press.tell(Jam)
                press.ask(1.minutes) { Stamped(it) }
            }

            answer shouldBeRight AskFailure.Stopped.left()
        }
    }
}
