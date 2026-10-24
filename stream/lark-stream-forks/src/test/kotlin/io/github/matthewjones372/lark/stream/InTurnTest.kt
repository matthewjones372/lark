package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldThrow
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Several producers, a ring each, and one reader taking from the rings in turn and parking on all of them. */
class InTurnTest {

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

    /** What each ring's producer put, as `ring * COUNT + n`, split back by ring. */
    private fun byRing(taken: List<Any?>, rings: Int) =
        List(rings) { ring -> taken.map { it as Int }.filter { it / COUNT == ring } }

    @Test
    fun `no wake-up is lost while every side keeps running dry and parking`() {
        for (rings in listOf(2, 3)) {
            for (capacity in listOf(1, 2, 16)) {
                val ring = Array(rings) { Ring(capacity) }
                // Each side pauses now and then, so the reader finds every ring empty and parks, and each producer
                // finds its ring full and parks, over and over.
                ring.forEachIndexed { r, into ->
                    onVirtualThread {
                        repeat(COUNT) {
                            if (it % (PRODUCER_PAUSES_EVERY + r) == 0) LockSupport.parkNanos(PAUSE_NANOS)
                            into.put(r * COUNT + it)
                        }
                    }
                }
                val reading = InTurn(ring)
                val taken = onVirtualThread {
                    List(rings * COUNT) {
                        if (it % READER_PAUSES_EVERY == 0) LockSupport.parkNanos(PAUSE_NANOS)
                        reading.next()
                    }
                }
                withClue("$rings rings of $capacity") {
                    byRing(taken.get(GENEROUS_SECONDS, TimeUnit.SECONDS), rings) shouldBe
                        List(rings) { r -> List(COUNT) { r * COUNT + it } }
                }
            }
        }
    }

    @Test
    fun `a reader takes from each ring in turn while more than one has elements`() {
        val rings = Array(2) { Ring(4) }
        repeat(4) { rings[0].put("a$it") }
        repeat(4) { rings[1].put("b$it") }
        val reading = InTurn(rings)

        List(8) { reading.next() } shouldBe listOf("a0", "b0", "a1", "b1", "a2", "b2", "a3", "b3")
    }

    @Test
    fun `a reader parked on every ring is woken by one element on whichever ring it comes`() {
        for (which in 0..2) {
            val rings = Array(3) { Ring(16) }
            val reading = InTurn(rings)
            val taken = CompletableFuture<Any?>()
            val reader = Thread.ofVirtual().start { taken.complete(reading.next()) }
            waitUntilParked(reader)

            rings[which].put("only")

            withClue("ring $which") { taken.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe "only" }
        }
    }

    @Test
    fun `an interrupt reaches a reader parked on every ring`() {
        val reading = InTurn(Array(2) { Ring(2) })
        val reader = Thread.ofVirtual().start {
            shouldThrow<InterruptedException> { reading.next() }
        }
        waitUntilParked(reader)

        reader.interrupt()

        reader.join(TimeUnit.SECONDS.toMillis(GENEROUS_SECONDS))
        reader.isAlive shouldBe false
    }

    private companion object {
        const val COUNT = 20_000
        const val GENEROUS_SECONDS = 30L
        const val PAUSE_NANOS = 20_000L
        const val PRODUCER_PAUSES_EVERY = 7
        const val READER_PAUSES_EVERY = 11
    }
}
