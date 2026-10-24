package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.VirtualThreads
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import io.github.matthewjones372.lark.assert.shouldThrow
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** One body on its own thread, waited on by the pull and let go of by the run. */
class InFlightTest {

    private fun waitUntilParked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
        while (thread.state != Thread.State.WAITING) {
            check(System.nanoTime() < deadline) { "$thread never parked" }
            Thread.onSpinWait()
        }
    }

    // The catch is as wide as the body: whatever it threw is what the test reads from the future.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> onVirtualThread(body: () -> T): CompletableFuture<T> {
        val answer = CompletableFuture<T>()
        Thread.ofVirtual().start {
            try {
                answer.complete(body())
            } catch (thrown: Throwable) {
                answer.completeExceptionally(thrown)
            }
        }
        return answer
    }

    @Test
    fun `a pull parked on a body is woken by its answer`() {
        val release = CountDownLatch(1)
        val flight = InFlight(VirtualThreads) { release.await(); "answer" }
        val awaited = CompletableFuture<String>()
        val puller = Thread.ofVirtual().start { awaited.complete(flight.await()) }
        waitUntilParked(puller)

        release.countDown()

        awaited.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe "answer"
    }

    @Test
    fun `no wake is lost while answers race the pulls waiting for them`() {
        val count = 20_000
        val answers = onVirtualThread {
            List(count) { i ->
                // Some bodies finish before the pull looks, some while it yields, some once it has parked.
                val flight = InFlight(VirtualThreads) {
                    if (i % BODY_PAUSES_EVERY == 0) LockSupport.parkNanos(PAUSE_NANOS)
                    i
                }
                if (i % PULL_PAUSES_EVERY == 0) LockSupport.parkNanos(PAUSE_NANOS)
                flight.await()
            }
        }

        answers.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe List(count) { it }
    }

    @Test
    fun `whenDone runs once, whether the body answered before it was asked or after`() {
        repeat(ROUNDS) { round ->
            val ran = AtomicInteger()
            val done = CountDownLatch(1)
            val flight = InFlight(VirtualThreads) { if (round % 2 == 0) LockSupport.parkNanos(PAUSE_NANOS) }
            if (round % 3 == 0) flight.await()
            flight.whenDone {
                ran.incrementAndGet()
                done.countDown()
            }
            done.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
            flight.await()
            withClue("round $round") { ran.get() shouldBe 1 }
        }
    }

    @Test
    fun `what the body threw is what await throws, as it threw it`() {
        val failure = IllegalStateException("the body failed")
        val flight = InFlight<Unit>(VirtualThreads) { throw failure }

        val thrown = shouldThrow<IllegalStateException> { flight.await() }

        (thrown === failure) shouldBe true
    }

    @Test
    fun `an interrupt reaches a pull parked on a body that has not answered`() {
        val flight = InFlight(VirtualThreads) { Thread.sleep(Long.MAX_VALUE) }
        val outcome = CompletableFuture<Throwable?>()
        val puller = Thread.ofVirtual().start {
            try {
                flight.await()
                outcome.complete(null)
            } catch (thrown: InterruptedException) {
                outcome.complete(thrown)
            }
        }
        waitUntilParked(puller)

        puller.interrupt()

        outcome.get(GENEROUS_SECONDS, TimeUnit.SECONDS).shouldBeInstanceOf<InterruptedException>()
        flight.cancel()
    }

    @Test
    fun `cancel interrupts a running body and returns only once it has stopped`() {
        val started = CountDownLatch(1)
        val stopped = AtomicInteger()
        val flight = InFlight(VirtualThreads) {
            started.countDown()
            try {
                Thread.sleep(Long.MAX_VALUE)
            } finally {
                // Still running after the interrupt: cancel must wait for this.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(STOPPING_MILLIS))
                stopped.incrementAndGet()
            }
        }
        started.await()

        flight.cancel()

        stopped.get() shouldBe 1
        shouldThrow<InterruptedException> { flight.await() }
    }

    @Test
    fun `a body cancelled before it started never runs, and await says it was cancelled`() {
        val queued = mutableListOf<Runnable>()
        val held = Executor { queued.add(it) }
        val ran = AtomicInteger()
        val flight = InFlight(held) { ran.incrementAndGet() }
        val canceller = Thread.ofVirtual().start { flight.cancel() }
        waitUntilParked(canceller)

        queued.single().run()

        canceller.join(TimeUnit.SECONDS.toMillis(GENEROUS_SECONDS))
        canceller.isAlive shouldBe false
        ran.get() shouldBe 0
        shouldThrow<CancellationException> { flight.await() }
    }

    @Test
    fun `a cancel interrupted while it waits still waits, and keeps the interrupt`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flight = InFlight(VirtualThreads) {
            started.countDown()
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                release.await()
            }
        }
        started.await()
        val keptInterrupt = CompletableFuture<Boolean>()
        val canceller = Thread.ofVirtual().start {
            flight.cancel()
            keptInterrupt.complete(Thread.currentThread().isInterrupted)
        }
        waitUntilParked(canceller)
        canceller.interrupt()
        Thread.sleep(STOPPING_MILLIS)
        keptInterrupt.isDone shouldBe false

        release.countDown()

        keptInterrupt.get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    private companion object {
        const val GENEROUS_SECONDS = 30L
        const val PAUSE_NANOS = 20_000L
        const val STOPPING_MILLIS = 50L
        const val BODY_PAUSES_EVERY = 7
        const val PULL_PAUSES_EVERY = 11
        const val ROUNDS = 2_000
    }
}
