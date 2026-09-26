package io.github.matthewjones372.lark.stream

import arrow.core.left
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.actor.ActorSystem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A hub on every backend: what reaches whom, what is refused, and when a subscription ends. */
class HubTest {

    companion object {
        private val system: ActorSystem = ActorSystem.create("lark-stream-hub-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
            system.getWhenTerminated().toCompletableFuture().join()
        }

        private const val SETTLE_SECONDS = 10L
    }

    private val backends: List<StreamBackend> = listOf(PekkoStreams(system), Forks(), TestStreams(), Actors(heldFlock))

    private fun onEvery(check: (StreamBackend) -> Unit): List<DynamicTest> =
        backends.map { backend -> dynamicTest(backend.key.name) { check(backend) } }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    /** Waits until [n] runs have subscribed: a run registers when it starts, not when it is described. */
    private fun Hub<*>.awaitSubscribers(n: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_SECONDS)
        while (subscribers != n) {
            check(System.nanoTime() < until) { "$subscribers subscribed, waiting for $n" }
            Thread.onSpinWait()
        }
    }

    @TestFactory
    fun `what is published before anyone subscribes goes to the first subscriber, in order`() = onEvery { backend ->
        val hub = Hub<Int>()
        hub.publish(1)
        hub.publish(2)

        val first = hub.subscribe().take(3).runCollect().start(backend)
        hub.awaitSubscribers(1)
        hub.publish(3)

        first.exit.settled() shouldBe Exit.Done(listOf(1, 2, 3))
    }

    @TestFactory
    fun `every subscriber gets every element published while it runs`() = onEvery { backend ->
        val hub = Hub<Int>()
        val one = hub.subscribe().runCollect().start(backend)
        val two = hub.subscribe().runCollect().start(backend)
        hub.awaitSubscribers(2)

        (1..5).forEach { hub.publish(it) shouldBe it.right() }
        hub.close()

        one.exit.settled() shouldBe Exit.Done(listOf(1, 2, 3, 4, 5))
        two.exit.settled() shouldBe Exit.Done(listOf(1, 2, 3, 4, 5))
    }

    @TestFactory
    fun `a full subscriber turns an element away for every subscriber`() = onEvery { backend ->
        val hub = Hub<Int>(capacity = 2)
        val stalled = Gate()
        val slow = hub.subscribe().map { stalled.await(); it }.runCollect().start(backend)
        val quick = hub.subscribe().runCollect().start(backend)
        hub.awaitSubscribers(2)

        // The slow reader holds one in its map, and its queue fills behind it: two, and on Pekko the
        // input buffer it reads ahead into as well. Fifty is more than any of them holds.
        val answers = (1..50).map { hub.publish(it).also { Thread.sleep(5) } }
        stalled.open()
        hub.close()

        val refused = answers.count { it == HubRefused.Full.left() }
        withClue("answers: $answers") { (refused > 0) shouldBe true }
        val accepted = answers.mapNotNull { it.getOrNull() }
        withClue("a refused element went to nobody, and an accepted one to both") {
            slow.exit.settled() shouldBe Exit.Done(accepted)
            quick.exit.settled() shouldBe Exit.Done(accepted)
        }
    }

    @TestFactory
    fun `a subscriber that has stopped no longer holds the hub back`() = onEvery { backend ->
        val hub = Hub<Int>(capacity = 1)
        val gone = hub.subscribe().runCollect().start(backend)
        hub.awaitSubscribers(1)

        gone.stop()
        gone.exit.settled()
        hub.awaitSubscribers(0)

        withClue("nobody reads the stopped subscriber's queue, so it would be full after one") {
            (1..3).map { hub.publish(it) } shouldBe listOf(1.right(), 2.right(), 3.right())
        }
    }

    @TestFactory
    fun `closing the hub ends every subscription Done, after what it was sent`() = onEvery { backend ->
        val hub = Hub<String>()
        val reading = hub.subscribe().runCollect().start(backend)
        hub.awaitSubscribers(1)
        hub.publish("a")

        hub.close()

        reading.exit.settled() shouldBe Exit.Done(listOf("a"))
        hub.publish("b") shouldBe HubRefused.Closed.left()
        hub.subscribe().runCollect().run(backend).settled() shouldBe Exit.Done(emptyList())
    }

    @TestFactory
    fun `a stream published to a hub fails with the refusal`() = onEvery { backend ->
        val hub = Hub<Int>(capacity = 2)

        Stream.of(1, 2, 3).publishTo(hub).runCollect().run(backend).settled() shouldBe Exit.Failed(HubRefused.Full)
    }

    @Test
    fun `a hub holds at least one element for each subscriber`() {
        shouldThrow<IllegalArgumentException> { Hub<Int>(capacity = 0) }
    }

    /** Holds every caller of [await] until [open]. */
    private class Gate {
        private val latch = CountDownLatch(1)

        fun await() {
            latch.await(SETTLE_SECONDS, TimeUnit.SECONDS)
        }

        fun open() = latch.countDown()
    }
}
