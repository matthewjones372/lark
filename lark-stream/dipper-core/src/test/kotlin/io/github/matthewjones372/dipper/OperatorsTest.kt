package io.github.matthewjones372.dipper

import arrow.core.Either
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.pekko.stream.javadsl.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the asynchronous and splitting operators promise, asserted on a real system. */
class OperatorsTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("dipper-operators-test")
    }

    private data class Receipt(val id: Int)

    private data class Declined(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun settle(id: Int): CompletionStage<Receipt> = CompletableFuture.completedFuture(Receipt(id))

    /** A ledger that answers null where its own type says it will not: the completion Pekko drops. */
    @Suppress("UNCHECKED_CAST")
    private fun nothingAtAll(): CompletionStage<Receipt> =
        CompletableFuture.completedFuture<Receipt?>(null) as CompletionStage<Receipt>

    private fun accepted(id: Int): Either<Declined, Receipt> = Either.Right(Receipt(id))

    private fun declined(id: Int): Either<Declined, Receipt> = Either.Left(Declined(id))

    @Test
    fun `mapAsync answers in the order of its input though the stages complete backwards`() {
        val gates = (1..4).associateWith { CompletableFuture<Receipt>() }
        gates.keys.reversed().forEach { id -> gates.getValue(id).complete(Receipt(id)) }

        val exit = Stream.from(gates.keys.toList())
            .mapAsync(4) { id -> gates.getValue(id) }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf(Receipt(1), Receipt(2), Receipt(3), Receipt(4)))
    }

    @Test
    fun `a stage that completes with null dies rather than dropping the element`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapAsync(1) { id -> if (id == 2) nothingAtAll() else settle(id) }
            .runCollect()
            .run(pekko.system)
            .settled()

        val died = withClue("Pekko would have answered Done with the other two elements") {
            exit.shouldBeInstanceOf<Exit.Died>()
        }
        died.cause.shouldBeInstanceOf<NullPointerException>()
        withClue("a defect that does not name where it came from is the disappearance again") {
            died.cause.message shouldContain "mapAsync"
        }
    }

    @Test
    fun `a stage that fails arrives as Exit Died carrying what failed it`() {
        val cause = IllegalStateException("no ledger")

        val exit = Stream.from(listOf(1, 2, 3))
            .mapAsync(2) { id ->
                if (id == 2) CompletableFuture.failedFuture<Receipt>(cause) else settle(id)
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Died(cause)
    }

    @Test
    fun `a declared failure before mapAsync still arrives as Exit Failed`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> if (id == 2) fail(Declined(id)) else id }
            .mapAsync(2) { id -> settle(id) }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Declined(2))
    }

    @Test
    fun `divertLefts counts every Left at the sink and carries every Right on`() {
        val counted = ConcurrentLinkedQueue<Declined>()
        val allThree = CountDownLatch(3)

        val exit = Stream.from(listOf(accepted(1), declined(2), declined(3), accepted(4), declined(5)))
            .divertLefts(
                to = Sink.foreach { declined: Declined ->
                    counted.add(declined)
                    allThree.countDown()
                },
            )
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf(Receipt(1), Receipt(4)))
        withClue("the sink is a branch of its own, so the run can finish before it has seen the last Left") {
            allThree.await(30, TimeUnit.SECONDS) shouldBe true
        }
        counted.toList() shouldBe listOf(Declined(2), Declined(3), Declined(5))
    }

    @Test
    fun `a declared failure upstream of divertLefts is still Exit Failed`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> if (id == 2) fail(Declined(id)) else accepted(id) }
            .divertLefts(to = Sink.ignore())
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Declined(2))
    }

    @Test
    fun `either makes the failure the last element and leaves nothing for the type to carry`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> if (id == 3) fail(Declined(id)) else Receipt(id) }
            .either()
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(
            listOf(Either.Right(Receipt(1)), Either.Right(Receipt(2)), Either.Left(Declined(3))),
        )
    }

    @Test
    fun `a defect is not a failure either can name, so it still dies`() {
        val cause = IllegalStateException("no ledger")

        val exit = Stream.from(listOf(1, 2, 3))
            .map { id -> if (id == 2) throw cause else Receipt(id) }
            .either()
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Died(cause)
    }

    @Test
    fun `absolve fails on the first Left and never reaches what follows it`() {
        val seen = ConcurrentLinkedQueue<Receipt>()

        val exit = Stream.from(listOf(accepted(1), declined(2), accepted(3)))
            .absolve()
            .map { receipt ->
                seen.add(receipt)
                receipt
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Declined(2))
        withClue("the Right after the Left must never have been looked at") {
            seen.toList() shouldBe listOf(Receipt(1))
        }
    }

    @Test
    fun `absolve after either gives back the failure it made an element of`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> if (id == 3) fail(Declined(id)) else Receipt(id) }
            .either()
            .absolve()
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Declined(3))
    }

    @Test
    fun `catchAll sees the declared failure and its stream carries on from there`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> if (id == 3) fail(Declined(id)) else Receipt(id) }
            .catchAll { declined -> Stream.from(listOf(Receipt(declined.id * 10))) }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf(Receipt(1), Receipt(2), Receipt(30)))
    }

    @Test
    fun `catchAll does not catch a defect`() {
        val cause = IllegalStateException("no ledger")

        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> if (id == 3) fail(Declined(id)) else Receipt(id) }
            .map { receipt -> if (receipt.id == 2) throw cause else receipt }
            .catchAll { declined -> Stream.from(listOf(Receipt(declined.id * 10))) }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Died(cause)
    }

    /** With the failure handled there is nothing left to declare, so the way out to Pekko opens. */
    @Test
    fun `a stream whose failure catchAll handled reaches toSource`() {
        val elements = Stream.from(listOf(1, 2))
            .mapOrFail { id -> if (id == 2) fail(Declined(id)) else Receipt(id) }
            .catchAll { Stream.empty() }
            .toSource()
            .runWith(Sink.seq(), pekko.system)
            .toCompletableFuture()
            .join()

        elements shouldBe listOf(Receipt(1))
    }

    @Test
    fun `orElse takes the other stream on a failure`() {
        val exit = Stream.from(listOf(1, 2))
            .mapOrFail { id -> if (id == 2) fail(Declined(id)) else Receipt(id) }
            .orElse(Stream.from(listOf(Receipt(9))))
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf(Receipt(1), Receipt(9)))
    }

    /** Pekko's `orElse` is about an empty stream; this one is about a failed one. */
    @Test
    fun `orElse leaves an empty stream empty`() {
        val exit = Stream.empty()
            .orElse(Stream.from(listOf(Receipt(9))))
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(emptyList())
    }
}
