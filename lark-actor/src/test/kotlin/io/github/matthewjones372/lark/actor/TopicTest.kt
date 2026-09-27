package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.capturingMetrics
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class TopicTest {

    private val heard = ConcurrentHashMap<String, ConcurrentLinkedQueue<Int>>()

    private fun listener(name: String) = behaviour<Int, Unit>(Unit) { _, _, n ->
        stay().also { heard.computeIfAbsent(name) { ConcurrentLinkedQueue() } += n }
    }

    @Test
    fun `every subscriber hears every publish in order, and one that stops is dropped without a dead letter`() {
        val letters = ConcurrentLinkedQueue<DeadLetter>()

        capturingMetrics { captured ->
            flock<Nothing, Unit> {
                onDeadLetter(letters::add)
                val prices = topic<Int>("prices")
                val listeners = listOf("a", "b", "c").map { spawn(it, listener(it)) }
                listeners.forEach(prices::subscribe)
                (1..50).forEach(prices::publish)
                awaitIdle()

                stop(listeners.last()).await()
                awaitIdle()
                (51..60).forEach(prices::publish)
                prices.unsubscribe(listeners[1])
                (61..70).forEach(prices::publish)
                awaitIdle()
            }

            heard.getValue("a").toList() shouldContainExactly (1..70).toList()
            heard.getValue("b").toList() shouldContainExactly (1..60).toList()
            heard.getValue("c").toList() shouldContainExactly (1..50).toList()
            letters.toList() shouldBe emptyList()
            captured.counter("lark.topic.published") shouldBe 70.0
            captured.counter("lark.topic.delivered") shouldBe (50.0 * 3 + 10 * 2 + 10)
            captured.gauge("lark.topic.subscribers") shouldBe 1.0
        }
    }

    @Test
    fun `a publish is handed on, and a message that arrives is heard here and handed on no further`() {
        val forwarded = ConcurrentLinkedQueue<Int>()

        flock<Nothing, Unit> {
            val prices = topic<Int>("prices") { forwarded += it }
            prices.subscribe(spawn("a", listener("a")))
            prices.publish(1)
            prices.ref.tell(TopicMessage.Arrive(2))
            awaitIdle()
        }

        heard.getValue("a").toList() shouldContainExactly listOf(1, 2)
        forwarded.toList() shouldContainExactly listOf(1)
    }

    /** A sharded entity, a singleton or a group: a ref that is not an actor here and cannot be watched. */
    private class Elsewhere(val heard: ConcurrentLinkedQueue<Int>) : ActorRef<Int> {
        override val address = Address("", "/elsewhere", 0)

        override fun tell(message: Int) {
            heard += message
        }
    }

    @Test
    fun `a subscriber that cannot be watched hears every publish until it unsubscribes, and the topic lives on`() {
        val elsewhere = Elsewhere(ConcurrentLinkedQueue())

        flock<Nothing, Unit> {
            val prices = topic<Int>("prices")
            prices.subscribe(elsewhere)
            prices.subscribe(spawn("a", listener("a")))
            (1..3).forEach(prices::publish)
            awaitIdle()
            prices.unsubscribe(elsewhere)
            prices.publish(4)
            awaitIdle()
        }

        elsewhere.heard.toList() shouldContainExactly listOf(1, 2, 3)
        heard.getValue("a").toList() shouldContainExactly listOf(1, 2, 3, 4)
    }
}
