package io.github.matthewjones372.lark.stream

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One producer and one consumer across a ring that wakes either side only when it has to. */
class RingTest {

    private fun <T> onVirtualThread(body: () -> T): CompletableFuture<T> {
        val answer = CompletableFuture<T>()
        Thread.ofVirtual().start {
            try {
                answer.complete(body())
            } catch (thrown: InterruptedException) {
                answer.completeExceptionally(thrown)
            }
        }
        return answer
    }

    private fun waitUntilParked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
        while (thread.state != Thread.State.WAITING) {
            check(System.nanoTime() < deadline) { "$thread never parked" }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `every element arrives once, in order, whatever the capacity`() {
        for (capacity in listOf(1, 3, 4, 256)) {
            val ring = Ring(capacity)
            val count = 100_000
            onVirtualThread { repeat(count) { ring.put(it) } }
            val taken = onVirtualThread { List(count) { ring.take() } }.get(GENEROUS_SECONDS, TimeUnit.SECONDS)
            withClue("capacity $capacity") { taken shouldBe List(count) { it } }
        }
    }

    @Test
    fun `a producer runs ahead by the capacity and no further`() {
        val ring = Ring(4)
        val put = AtomicInteger()
        val producer = Thread.ofVirtual().start {
            repeat(10) {
                ring.put(it)
                put.incrementAndGet()
            }
        }
        waitUntilParked(producer)

        put.get() shouldBe 4
        List(10) { ring.take() } shouldBe List(10) { it }
    }

    @Test
    fun `a consumer parked on an empty ring is woken by one element`() {
        val ring = Ring(256)
        val taken = onVirtualThread { ring.take() }
        Thread.sleep(50)

        ring.put("only")

        taken.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe "only"
    }

    @Test
    fun `a producer parked on a full ring is woken once the consumer has made room`() {
        val ring = Ring(8)
        val produced = onVirtualThread { repeat(100) { ring.put(it) } }
        val taken = List(100) { ring.take() }

        produced.get(GENEROUS_SECONDS, TimeUnit.SECONDS)
        taken shouldBe List(100) { it }
    }

    @Test
    fun `an interrupt reaches a producer parked on a full ring`() {
        val ring = Ring(2)
        val outcome = CompletableFuture<Throwable?>()
        val producer = Thread.ofVirtual().start {
            try {
                repeat(3) { ring.put(it) }
                outcome.complete(null)
            } catch (thrown: InterruptedException) {
                outcome.complete(thrown)
            }
        }
        waitUntilParked(producer)

        producer.interrupt()

        outcome.get(GENEROUS_SECONDS, TimeUnit.SECONDS).shouldBeInstanceOf<InterruptedException>()
    }

    @Test
    fun `an interrupt reaches a consumer parked on an empty ring`() {
        val ring = Ring(2)
        val consumer = Thread.ofVirtual().start {
            shouldThrow<InterruptedException> { ring.take() }
        }
        waitUntilParked(consumer)

        consumer.interrupt()

        consumer.join(TimeUnit.SECONDS.toMillis(GENEROUS_SECONDS))
        consumer.isAlive shouldBe false
    }

    @Test
    fun `an interrupted producer stops at its next put even when there is room`() {
        val ring = Ring(256)
        Thread.currentThread().interrupt()

        shouldThrow<InterruptedException> { ring.put(1) }
        Thread.interrupted() shouldBe false
        ring.poll() shouldBe null
    }

    private companion object {
        const val GENEROUS_SECONDS = 30L
    }
}
