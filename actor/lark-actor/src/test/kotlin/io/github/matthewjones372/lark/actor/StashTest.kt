package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.github.matthewjones372.lark.assert.shouldThrow
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.flock
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

private sealed interface Call

private data object Ringing : Call

private data class Answered(val heard: List<Int> = emptyList()) : Call

private sealed interface Line

private data class Say(val n: Int) : Line

/** Answers, and tells itself one more word before it unstashes, so that word is in the mailbox behind the stash. */
private data object PickUp : Line

private data class Transcript(val reply: Reply<List<Int>>) : Line

private data object Static : Line

/** A call that keeps what is said while it rings, and hears it once answered. */
private fun phone() = behaviour<Line, Call>(Ringing) { ctx, call, message ->
    when (call) {
        Ringing -> when (message) {
            is Say, is Transcript -> {
                ctx.stash(message)
                stay()
            }

            PickUp -> {
                ctx.self.tell(Say(99))
                ctx.unstashAll()
                become(Answered())
            }

            Static -> error("the line crackled")
        }

        is Answered -> when (message) {
            is Say -> become(Answered(call.heard + message.n))

            is Transcript -> {
                message.reply(call.heard)
                stay()
            }

            PickUp, Static -> unhandled()
        }
    }
}

class StashTest {

    @Test
    fun `unstashed messages are handled before the mailbox, in the order kept`() {
        val phone = phone().test()
        phone.send(Say(1))
        phone.send(Say(2))

        phone.send(PickUp)

        phone.state shouldBe Answered(listOf(1, 2, 99))
    }

    @Test
    fun `stashing into a full stash fails the step`() {
        val phone = phone().test(stash = 2)
        phone.send(Say(1))
        phone.send(Say(2))

        shouldThrow<IllegalStateException> { phone.send(Say(3)) }

        phone.failure.shouldBeInstanceOf<Failure.Thrown>()
    }

    @Test
    fun `a restart drops the stash`() {
        val phone = phone().test(restart = Schedule.recurs(1))
        phone.send(Say(1))
        phone.send(Say(2))

        phone.send(Static)
        phone.send(PickUp)

        phone.restarts shouldBe 1
        phone.state shouldBe Answered(listOf(99))
    }

    @Test
    fun `on threads, unstashed messages are handled before the mailbox, in the order kept`() {
        val heard = flock<Nothing, Any> {
            val phone = spawn("phone", phone())
            phone.tell(Say(1))
            phone.tell(Say(2))
            phone.tell(PickUp)
            // PickUp's own Say(99) reaches the mailbox as it is handled; an ask told before then is ahead of it.
            awaitIdle()
            phone.ask(1.minutes) { Transcript(it) }
        }

        heard shouldBeRight listOf(1, 2, 99).right()
    }

    @Test
    fun `on threads, stashing into a full stash fails the step`() {
        val heard = flock<Nothing, Any> {
            val phone = spawn("phone", phone(), stash = 2)
            (1..3).forEach { phone.tell(Say(it)) }
            phone.ask(1.minutes) { Transcript(it) }
        }

        heard shouldBeRight AskFailure.Stopped.left()
    }
}
