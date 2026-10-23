package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Forks' unordered windows: each answer passes on as soon as it is done, and nothing waits on the oldest. */
class UnorderedTest {

    private data class Odd(val value: Int)

    private fun <E, R> Running<E, R>.settled(): Exit<E, R> =
        exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "never saw $what" }
            Thread.sleep(1)
        }
    }

    @Test
    fun `mapParUnordered passes on the quick bodies while the first is still running`() {
        val firstMayFinish = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<Int>()
        val running = Stream.of(1, 2, 3)
            .mapParUnordered(3) { n -> n.also { if (n == 1) firstMayFinish.await() } }
            .map { n -> n.also { seen.add(it) } }
            .runCollect()
            .start(Forks())

        waitFor("2 and 3 passed on") { seen.size == 2 }
        withClue("the slow first body held back nothing") { seen.toSet() shouldBe setOf(2, 3) }
        firstMayFinish.countDown()

        val exit = running.settled()
        (exit as Exit.Done).value.last() shouldBe 1
    }

    @Test
    fun `mapAsyncUnordered passes on the quick stages while the first is still pending`() {
        val first = CompletableFuture<Int>()
        val seen = CopyOnWriteArrayList<Int>()
        val running = Stream.of(1, 2, 3)
            .mapAsyncUnordered(3) { n -> if (n == 1) first else CompletableFuture.completedFuture(n) }
            .map { n -> n.also { seen.add(it) } }
            .runCollect()
            .start(Forks())

        waitFor("2 and 3 passed on") { seen.size == 2 }
        withClue("the pending first stage held back nothing") { seen.toSet() shouldBe setOf(2, 3) }
        first.complete(1)

        (running.settled() as Exit.Done).value.last() shouldBe 1
    }

    @Test
    fun `no more than the parallelism run at once`() {
        val now = AtomicInteger()
        val most = AtomicInteger()
        val exit = Stream.from(1..200)
            .mapParUnordered(4) { n ->
                most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                Thread.sleep(1)
                now.decrementAndGet()
                n
            }
            .runFold(0) { sum, n -> sum + n }
            .start(Forks())
            .settled()

        exit shouldBe Exit.Done((1..200).sum())
        withClue("the most bodies ever running at once") { (most.get() <= 4) shouldBe true }
    }

    @Test
    fun `a declared failure ends the run with it, and interrupts the bodies still running`() {
        val interrupted = CountDownLatch(1)
        val odd: Stream<Odd, Int> = Stream.of(3, 2)
        val exit = odd
            .mapParUnordered(2) { n ->
                if (n == 3) raise(Odd(n))
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(SETTLE_SECONDS))
                } catch (stopped: InterruptedException) {
                    interrupted.countDown()
                    throw stopped
                }
                n
            }
            .runCollect()
            .start(Forks())
            .settled()

        exit shouldBe Exit.Failed(Odd(3))
        interrupted.await(SETTLE_SECONDS, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `the end of upstream arrives after every answer, whatever order they came in`() {
        val exit = Stream.from(1..1_000)
            .mapAsyncUnordered(16) { n -> CompletableFuture.supplyAsync { n } }
            .runCollect()
            .start(Forks())
            .settled()

        (exit as Exit.Done).value.sorted() shouldBe (1..1_000).toList()
    }

    private companion object {
        const val SETTLE_SECONDS = 10L
    }
}
