package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.github.matthewjones372.lark.flock
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

/** Asks the counter what it has counted. */
private data class CountAsked(val reply: Reply<Int>)

class CloseHookTest {

    @Test
    fun `a close hook asks an actor and has its answer before the flock stops it`() {
        val answered = ConcurrentLinkedQueue<Int>()

        flock<Nothing, Unit> {
            val counted = behaviour<CountAsked, Int>(3) { _, count, asked -> stay().also { asked.reply(count) } }
            val counter = spawn("counter", counted)
            onClose { answered += counter.ask(1.minutes) { CountAsked(it) }.getOrNull()!! }
        } shouldBeRight Unit

        answered.toList() shouldBe listOf(3)
    }
}
