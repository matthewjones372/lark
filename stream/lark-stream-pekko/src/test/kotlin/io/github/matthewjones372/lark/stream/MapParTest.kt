package io.github.matthewjones372.lark.stream

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.apache.pekko.stream.javadsl.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A blocking body per element, forked: what runs it, in what order, and what ends it. */
class MapParTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-mappar-test")

        /** Long enough that a machine under load does not fail a claim about interruption. */
        private const val GENEROUS_SECONDS = 30L
    }

    private data class Declined(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /** A ledger that declines the second id, so a body can both bind and fail. */
    private fun settle(id: Int): Either<Declined, String> =
        if (id == 2) Declined(id).left() else "receipt-$id".right()

    private fun paid(id: Int): Either<Declined, String> = "receipt-$id".right()

    @Test
    fun `every body runs at once, on a virtual thread, and the elements keep their order`() {
        val allFour = CountDownLatch(4)
        val virtual = ConcurrentLinkedQueue<Boolean>()

        val exit = Stream.from(listOf(1, 2, 3, 4))
            .mapParOrFail(4) { id ->
                virtual.add(Thread.currentThread().isVirtual)
                allFour.countDown()
                // Bodies run one after another would never all reach this, so the claim of
                // parallelism is made by the run answering at all rather than by a clock.
                allFour.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                paid(id).bind()
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf("receipt-1", "receipt-2", "receipt-3", "receipt-4"))
        withClue("the default executor is lark's, one virtual thread per body") {
            virtual.toList() shouldBe List(4) { true }
        }
    }

    @Test
    fun `a raise in one body fails the stream with its error and nothing after it is processed`() {
        val seen = ConcurrentLinkedQueue<String>()

        val exit = Stream.from(listOf(1, 2, 3, 4))
            .mapParOrFail(1) { id -> if (id == 2) raise(Declined(id)) else "receipt-$id" }
            .map { receipt ->
                seen.add(receipt)
                receipt
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Declined(2))
        withClue("the element before the failure is the only one that reached the next operator") {
            seen.toList() shouldBe listOf("receipt-1")
        }
    }

    @Test
    fun `a bind on a Left inside a body fails the stream with what the Left holds`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapParOrFail(2) { id -> settle(id).bind() }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Declined(2))
    }

    @Test
    fun `a throw in a body arrives as Exit Died carrying the same throwable`() {
        val cause = IllegalStateException("no ledger")

        val exit = Stream.from(listOf(1, 2, 3))
            .mapParOrFail(2) { id -> if (id == 2) throw cause else paid(id).bind() }
            .runCollect()
            .run(pekko.system)
            .settled()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        died.cause shouldBeSameInstanceAs cause
    }

    @Test
    fun `on names the executor every body runs on`() {
        val threads = ConcurrentLinkedQueue<String>()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ledger-1") }

        val exit = try {
            Stream.from(listOf(1, 2, 3))
                .mapParOrFail(2) { id -> paid(id).bind() }
                .mapPar(2, on = executor) { receipt ->
                    threads.add(Thread.currentThread().name)
                    receipt.uppercase()
                }
                .runCollect()
                .run(pekko.system)
                .settled()
        } finally {
            executor.shutdown()
        }

        exit shouldBe Exit.Done(listOf("RECEIPT-1", "RECEIPT-2", "RECEIPT-3"))
        withClue("a body runs where the call said it would, not on a virtual thread of lark's") {
            threads.toList() shouldBe List(3) { "ledger-1" }
        }
    }

    @Test
    fun `parallelism of one runs the bodies one at a time`() {
        val inFlight = AtomicInteger()
        val most = ConcurrentLinkedQueue<Int>()

        val exit = Stream.from(listOf(1, 2, 3, 4))
            .mapParOrFail(1) { id ->
                most.add(inFlight.incrementAndGet())
                Thread.sleep(20)
                inFlight.decrementAndGet()
                paid(id).bind()
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf("receipt-1", "receipt-2", "receipt-3", "receipt-4"))
        withClue("mapAsync's parallelism is mapPar's: one at a time means one body running") {
            most.toList().max() shouldBe 1
        }
    }

    /** The failure a stream already declares is the one its bodies raise, so no second type appears. */
    @Test
    fun `a failure declared upstream still arrives as Exit Failed through mapPar`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> if (id == 3) fail(Declined(id)) else id }
            .mapPar(2) { id -> "receipt-$id" }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Declined(3))
    }

    /**
     * The one thing a `CompletionStage` cannot say on its own: the stream that asked for this element
     * has gone, so the thread the body is blocking on is interrupted rather than left running.
     */
    @Test
    fun `a stream torn down interrupts a body still running, and the body sees it`() {
        val allFour = CountDownLatch(4)
        val never = CountDownLatch(1)
        val interrupted = CountDownLatch(3)

        val first = Stream.from(listOf(1, 2, 3, 4))
            .mapParOrFail(4) { id ->
                allFour.countDown()
                if (id == 1) {
                    allFour.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                } else {
                    try {
                        never.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                    } catch (stop: InterruptedException) {
                        interrupted.countDown()
                    }
                }
                paid(id).bind()
            }
            .catchAll { Stream.empty() }
            .toSource()
            // Fewer elements than the run started bodies for: the take cancels upstream with three
            // of them still blocking.
            .take(1)
            .runWith(Sink.seq(), pekko.system)
            .toCompletableFuture()
            .join()

        first shouldBe listOf("receipt-1")
        withClue("every body still running when the stream ended was interrupted where it blocked") {
            interrupted.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
        }
    }
}
