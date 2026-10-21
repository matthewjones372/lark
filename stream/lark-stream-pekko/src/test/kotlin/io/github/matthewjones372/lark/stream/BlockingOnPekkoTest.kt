package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A blocking source on Pekko: read on the blocking-IO dispatcher, woken by stop(), closed once. */
class BlockingOnPekkoTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-blocking-on-pekko-test")

        private const val GENEROUS_SECONDS = 30L
    }

    private class Queue(vararg items: Int) {
        val items = ArrayDeque(items.toList())
        val waiting = CountDownLatch(1)
        val woken = CountDownLatch(1)
        val closed = AtomicInteger()

        @Volatile
        var readOn: String = ""

        fun next(): Int? {
            readOn = Thread.currentThread().name
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
    fun `a resource that runs out ends the stream, read on the blocking-IO dispatcher, and closed once`() {
        val queue = Queue(1, 2, 3).also { it.woken.countDown() }

        blocking(queue).runCollect().run(pekko.system).settled() shouldBe Exit.Done(listOf(1, 2, 3))

        withClue("a blocked read must never hold one of the stream's own threads") {
            queue.readOn shouldContain "blocking-io"
        }
        queue.closed.get() shouldBe 1
    }

    @Test
    fun `a stop while next is blocked wakes it, and what was read still reaches the sink`() {
        val queue = Queue(1, 2)
        val running = blocking(queue).runCollect().start(pekko.system)
        queue.waiting.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

        running.stop()

        running.exit.settled() shouldBe Exit.Done(listOf(1, 2))
        queue.closed.get() shouldBe 1
    }

    @Test
    fun `a take that is satisfied closes the resource it stopped reading`() {
        val queue = Queue(1, 2, 3)

        blocking(queue).take(2).runCollect().run(pekko.system).settled() shouldBe Exit.Done(listOf(1, 2))

        withClue("Pekko closes a cancelled source after the sink completes; the exit waits for it, as on Forks") {
            queue.closed.get() shouldBe 1
        }
    }

    @Test
    fun `a close that throws ends a run that was otherwise Done as Died`() {
        val exit = Stream.blocking(
            open = { Unit },
            next = { _ -> null as Int? },
            wake = { },
            close = { _ -> error("the connection would not close") },
        ).runCollect().run(pekko.system).settled()

        (exit as Exit.Died).cause.message shouldBe "the connection would not close"
    }
}
