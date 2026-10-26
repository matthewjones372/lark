package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeEmpty
import io.github.matthewjones372.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val BALANCES = 10
private const val THREADS = 1_000
private const val TRANSFERS = 20
private const val READERS = 50
private const val WRITERS = 8
private const val WRITES = 2_000

class TransactTest {

    @Test
    fun `a step's writes are seen together once it commits`() {
        val stalls = Shared(120)
        val circle = Shared(60)

        transact {
            stalls.update { it - 2 }
            circle.update { it - 1 }
        }

        stalls.peek() shouldBe 118
        circle.peek() shouldBe 59
    }

    @Test
    fun `a step answers its block's value`() {
        val seats = Shared(3)

        transact { seats.get() * 2 } shouldBe 6
    }

    @Test
    fun `a step reads its own writes, and peek does not see them before the commit`() {
        val seats = Shared(10)

        val seen = transact {
            seats.set(7)
            Triple(seats.get(), seats.peek(), seats.update { it - 1 })
        }

        seen shouldBe Triple(7, 10, Unit)
        seats.peek() shouldBe 6
    }

    @Test
    fun `a body that throws commits nothing`() {
        val stalls = Shared(120)
        val circle = Shared(60)

        shouldThrow<IllegalStateException> {
            transact {
                stalls.set(0)
                circle.set(0)
                error("payment refused")
            }
        }

        stalls.peek() shouldBe 120
        circle.peek() shouldBe 60
    }

    @Test
    fun `transfers on a thousand virtual threads leave the total unchanged`() {
        val balances = List(BALANCES) { Shared(100) }

        val threads = List(THREADS) { t ->
            Thread.ofVirtual().start {
                repeat(TRANSFERS) { i ->
                    val from = balances[(t + i) % BALANCES]
                    val to = balances[(t + i + 1 + t % (BALANCES - 1)) % BALANCES]
                    transact {
                        from.update { it - 1 }
                        to.update { it + 1 }
                    }
                }
            }
        }
        threads.forEach { it.join() }

        balances.sumOf { it.peek() } shouldBe BALANCES * 100
    }

    @Test
    fun `a body never sees two balances from different commits`() {
        val balances = List(BALANCES) { Shared(100) }
        val torn = ConcurrentLinkedQueue<Int>()
        val runs = AtomicInteger()
        val writersDone = AtomicBoolean(false)

        // Writers yield after each commit: a virtual thread is never preempted, so writers that spin would keep the
        // readers off every carrier.
        val writers = List(WRITERS) { t ->
            Thread.ofVirtual().start {
                repeat(WRITES) { i ->
                    transact {
                        balances[(t + i) % BALANCES].update { it - 1 }
                        balances[(t + i + 1) % BALANCES].update { it + 1 }
                    }
                    Thread.yield()
                }
            }
        }
        // Readers keep reading for as long as anyone writes, so reads and commits overlap.
        val readers = List(READERS) {
            Thread.ofVirtual().start {
                while (!writersDone.get()) {
                    transact {
                        // Every run counts, including one that is about to retry.
                        val total = balances.sumOf { balance -> balance.get().also { Thread.yield() } }
                        runs.incrementAndGet()
                        if (total != BALANCES * 100) torn.add(total)
                    }
                }
            }
        }
        writers.forEach { it.join() }
        writersDone.set(true)
        readers.forEach { it.join() }

        withClue("no body read all the balances while writers ran") { (runs.get() > READERS) shouldBe true }
        withClue("totals a body saw that no commit ever left: $torn") { torn.shouldBeEmpty() }
    }
}
