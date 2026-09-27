package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Schedule
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A resource opened per run, read by blocking on the pulling thread, and closed once however the run ended. */
class BlockingTest {

    /** A resource whose reads block until there is something to read, or it is woken. */
    private class Queue(vararg items: Int) {
        val items = ArrayDeque(items.toList())
        val waiting = CountDownLatch(1)
        val woken = CountDownLatch(1)
        val closed = AtomicInteger()

        fun next(): Int? {
            items.removeFirstOrNull()?.let { return it }
            waiting.countDown()
            woken.await()
            return null
        }
    }

    private fun blocking(queue: Queue): Stream<Nothing, Int> =
        Stream.blocking(
            open = { queue },
            next = { it.next() },
            wake = { it.woken.countDown() },
            close = { it.closed.incrementAndGet() },
        )

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    @Test
    fun `a resource that runs out ends the stream, and is closed once`() {
        val queue = Queue(1, 2, 3).also { it.woken.countDown() }

        blocking(queue).runCollect().run(Forks()).settled() shouldBe Exit.Done(listOf(1, 2, 3))

        queue.closed.get() shouldBe 1
    }

    @Test
    fun `a stop while next is blocked wakes it, and the run ends Done with what arrived`() {
        val queue = Queue(1, 2)
        val running = blocking(queue).runCollect().start(Forks())
        queue.waiting.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

        running.stop()

        withClue("without wake the loop waits for a next that never returns") {
            running.exit.settled() shouldBe Exit.Done(listOf(1, 2))
        }
        queue.closed.get() shouldBe 1
    }

    @Test
    fun `a take that is satisfied closes the resource it stopped reading`() {
        val queue = Queue(1, 2, 3)

        blocking(queue).take(2).runCollect().run(Forks()).settled() shouldBe Exit.Done(listOf(1, 2))

        queue.closed.get() shouldBe 1
    }

    @Test
    fun `a next that throws without a wake is a defect, and the resource is still closed`() {
        val closed = AtomicInteger()
        val exit = Stream.blocking(
            open = { Unit },
            next = { _ -> error("the cursor broke") },
            wake = { },
            close = { closed.incrementAndGet() },
        ).runCollect().run(Forks()).settled()

        (exit as Exit.Died).cause.message shouldBe "the cursor broke"
        closed.get() shouldBe 1
    }

    @Test
    fun `a close that throws ends a run that was otherwise Done as Died`() {
        val exit = Stream.blocking(
            open = { Unit },
            next = { _ -> null as Int? },
            wake = { },
            close = { _ -> error("the connection would not close") },
        ).runCollect().run(Forks()).settled()

        (exit as Exit.Died).cause.message shouldBe "the connection would not close"
    }

    @Test
    fun `a read that fails closes its resource before restartOnDefect opens another`() {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val exit = Stream.blocking(
            open = { opened.incrementAndGet() },
            next = { n -> if (n == 1) error("the first connection dropped") else n.takeIf { closed.get() == 1 } },
            wake = { },
            close = { closed.incrementAndGet() },
        ).restartOnDefect(Schedule.recurs<Throwable>(1)).take(1).runCollect().run(Forks()).settled()

        exit shouldBe Exit.Done(listOf(2))
        withClue("the failed resource was closed before the second read, and the second when the run ended") {
            closed.get() shouldBe 2
        }
    }

    @Test
    fun `a stage that is done is passed on while the source blocks for the next element`() {
        val queue = Queue(1)

        val exit = blocking(queue)
            .mapAsync(4) { n -> CompletableFuture.completedFuture(n * 10) }
            .take(1)
            .runCollect()
            .run(Forks())
            .settled()

        exit shouldBe Exit.Done(listOf(10))
        withClue("the source was let go of while it blocked, and closed once") {
            queue.closed.get() shouldBe 1
        }
    }

    private companion object {
        const val GENEROUS_SECONDS = 30L
    }
}
