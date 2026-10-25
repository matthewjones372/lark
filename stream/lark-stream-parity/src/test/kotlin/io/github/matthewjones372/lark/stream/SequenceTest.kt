package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeLessThanOrEqual
import io.github.matthewjones372.lark.assert.shouldContain
import io.github.matthewjones372.lark.assert.shouldThrow
import io.github.matthewjones372.lark.assert.withClue
import org.apache.pekko.actor.ActorSystem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stream pulled as a `Sequence`, on every backend that runs apart from its caller. `TestStreams` runs a run on
 * the thread that starts it, so a buffer that fills would hold the reader's own thread.
 */
class SequenceTest {

    companion object {
        private val system: ActorSystem = ActorSystem.create("lark-stream-sequence-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
            system.getWhenTerminated().toCompletableFuture().join()
        }

        private const val BUFFER = 4

        /** How long a gate waits before the test fails: a guard against a hang, not a claim about speed. */
        private const val GUARD_SECONDS = 10L
    }

    private val backends: List<StreamBackend> = listOf(PekkoStreams(system), Forks(), Actors(heldFlock))

    private fun each(test: (StreamBackend) -> Unit): List<DynamicTest> =
        backends.map { backend -> dynamicTest(backend.key.name) { test(backend) } }

    private val naturals: Iterable<Int> = generateSequence(1) { it + 1 }.asIterable()

    private fun CountDownLatch.opens() = withClue("the gate never opened") {
        await(GUARD_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @TestFactory
    fun `a reader pulls every element, in order`() = each { backend ->
        Stream.from(1..100).toSequence(backend, BUFFER).toList() shouldBe (1..100).toList()
    }

    @TestFactory
    fun `a stalled reader holds the run at the buffer, and a reading one keeps it there`() = each { backend ->
        val pulled = AtomicInteger()
        val read = AtomicInteger()
        val furthestAhead = AtomicInteger()
        val full = CountDownLatch(1)
        val pulling = Stream.from(naturals).map { n ->
            val count = pulled.incrementAndGet()
            furthestAhead.accumulateAndGet(count - read.get(), ::maxOf)
            if (count == BUFFER + 1) full.countDown()
            n
        }

        val elements = pulling.toSequence(backend, BUFFER).iterator()
        full.opens()

        withClue("with nothing read, the buffer is full and one more element waits for room") {
            pulled.get() shouldBe BUFFER + 1
        }
        repeat(BUFFER * 10) {
            val _ = elements.next()
            read.incrementAndGet()
        }
        // Read before the close, which lets upstream run free until the stop reaches it.
        val ahead = furthestAhead.get()
        (elements as AutoCloseable).close()

        // One past buffer and the waiting element: a slot frees as an element is taken, before `read` counts it.
        withClue("upstream got $ahead ahead of the reader") { ahead shouldBeLessThanOrEqual BUFFER + 2 }
    }

    @TestFactory
    fun `closing mid-stream from another thread releases a full buffer and stops the run`() = each { backend ->
        val full = CountDownLatch(1)
        val released = CountDownLatch(1)
        val counted = AtomicInteger()
        val endless = Stream.blocking(
            open = { counted },
            // One read, then the buffer's worth, then one more that waits for room.
            next = { it.incrementAndGet().also { n -> if (n == BUFFER + 2) full.countDown() } },
            wake = { },
            close = { released.countDown() },
        )

        val elements = endless.toSequence(backend, BUFFER).iterator()
        elements.next() shouldBe 1
        full.opens()

        CompletableFuture.runAsync { (elements as AutoCloseable).close() }.get(GUARD_SECONDS, TimeUnit.SECONDS)

        withClue("the run's source was closed by the time close returned") { released.count shouldBe 0L }
        elements.hasNext() shouldBe false
    }

    @TestFactory
    fun `closing wakes a reader waiting on an empty buffer, and it sees the end`() = each { backend ->
        val asked = CountDownLatch(1)
        val never = LinkedBlockingQueue<Int>()
        val silent = Stream.blocking(
            open = { never },
            next = { queue ->
                asked.countDown()
                queue.take().takeIf { it >= 0 }
            },
            wake = { queue -> queue.put(-1) },
            close = { },
        )
        val elements = silent.toSequence(backend, BUFFER).iterator()
        val waiting = CompletableFuture.supplyAsync { elements.hasNext() }
        asked.opens()

        (elements as AutoCloseable).close()

        waiting.get(GUARD_SECONDS, TimeUnit.SECONDS) shouldBe false
    }

    @TestFactory
    fun `a run that dies throws its cause from the reader's next hasNext, after what it sent`() = each { backend ->
        val dying = Stream.of(1, 2, 3).map { n -> if (n == 3) error("third element refused") else n }
        val elements = dying.toSequence(backend, BUFFER).iterator()

        elements.next() shouldBe 1
        elements.next() shouldBe 2
        shouldThrow<IllegalStateException> { elements.hasNext() }.message shouldContain "third element refused"
    }

    @TestFactory
    fun `a second iteration throws`() = each { backend ->
        val once = Stream.of(1, 2).toSequence(backend, BUFFER)
        once.toList() shouldBe listOf(1, 2)

        shouldThrow<IllegalStateException> { once.iterator() }
    }

    @Test
    fun `a buffer with no room is refused`() {
        shouldThrow<IllegalArgumentException> { Stream.of(1).toSequence(Forks(), buffer = 0) }
            .message shouldContain "at least one element"
    }
}
