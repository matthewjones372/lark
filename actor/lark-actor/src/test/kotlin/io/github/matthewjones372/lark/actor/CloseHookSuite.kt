package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.resource
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

/** Asks the counter what it has counted. */
private data class CountAsked(val reply: Reply<Int>)

object CloseHookSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a close hook asks an actor and has its answer before the flock stops it") { _ ->
            val answered = ConcurrentLinkedQueue<Int>()

            val closed = flock<Nothing, Unit> {
                val counted = behaviour<CountAsked, Int>(3) { _, count, asked -> stay().also { asked.reply(count) } }
                val counter = spawn("counter", counted)
                onClose { answered += counter.ask(1.minutes) { CountAsked(it) }.getOrNull()!! }
            } shouldBeRight Unit

            closed and (answered.toList() shouldBe listOf(3))
        }
    }
}
