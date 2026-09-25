package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.stream.testkit.javadsl.TestSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

/** What a declared failure means, asserted through the public API on a real system. */
class StreamTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-stream-test")
    }

    private data class Row(val id: Int, val customer: String?)

    private data class NoCustomer(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private val rows = listOf(Row(1, "ada"), Row(2, null), Row(3, "grace"))

    @Test
    fun `fail in mapOrFail arrives at run as Exit Failed and no later element is processed`() {
        val seen = AtomicInteger()

        val exit = Stream.from(rows)
            .mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) }
            .map { customer ->
                seen.incrementAndGet()
                customer
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(NoCustomer(2))
        withClue("the row before the failure is the only one that reached the next operator") {
            seen.get() shouldBe 1
        }
    }

    @Test
    fun `a stream that already names a failure can fail again with it`() {
        val exit = Stream.from(rows)
            .mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) }
            .mapOrFail { customer -> if (customer == "ada") fail(NoCustomer(0)) else customer }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(NoCustomer(0))
    }

    @Test
    fun `a throw arrives at run as Exit Died carrying the cause`() {
        val cause = IllegalStateException("no ledger")

        val exit = Stream.from(rows)
            .map { row -> if (row.id == 2) throw cause else row }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Died(cause)
    }

    @Test
    fun `runFold answers Exit Done with what it folded`() {
        val exit = Stream.from(listOf(1, 2, 3, 4))
            .filter { n -> n % 2 == 0 }
            .runFold(0) { total, n -> total + n }
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(6)
    }

    @Test
    fun `runCollect answers Exit Done with every element in order`() {
        val exit = Stream.from(listOf("a", "b", "c"))
            .map { s -> s.uppercase() }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf("A", "B", "C"))
    }

    @Test
    fun `from an iterable and from a source carry the same elements`() {
        val fromIterable = Stream.from(listOf(1, 2, 3)).runCollect().run(pekko.system).settled()
        val fromSource = Stream.from(Source.from(listOf(1, 2, 3))).runCollect().run(pekko.system).settled()

        fromIterable shouldBe fromSource
    }

    @Test
    fun `fail on the companion ends the stream with the error it was given`() {
        val exit = Stream.fail(NoCustomer(7)).runCollect().run(pekko.system).settled()

        exit shouldBe Exit.Failed(NoCustomer(7))
    }

    @Test
    fun `an empty stream is Done with nothing in it`() {
        val exit = Stream.empty().runCollect().run(pekko.system).settled()

        exit shouldBe Exit.Done(emptyList())
    }

    @Test
    fun `a Run is a description and nothing runs until run is called`() {
        val seen = AtomicInteger()
        val described = Stream.from(listOf(1, 2, 3))
            .map { n ->
                seen.incrementAndGet()
                n
            }
            .runCollect()

        withClue("building the description must not touch a materializer") { seen.get() shouldBe 0 }

        described.run(pekko.system).settled()

        seen.get() shouldBe 3
    }

    @Test
    fun `toSource hands back a source that runs under plain Pekko`() {
        val source: Source<Int, NotUsed> = Stream.from(listOf(1, 2, 3)).map { n -> n * 2 }.toSource()

        val probe = source.runWith(TestSink.probe(pekko.classic), pekko.system)

        probe.request(3)
        probe.expectNext(2)
        probe.expectNext(4)
        probe.expectNext(6)
        probe.expectComplete()
    }

    /**
     * The wrapper `fail` travels in is an exception, so a stream that reached
     * plain Pekko carrying one would fail a sink nobody told about it. Only a
     * stream with no declared failure left can get there, and this is that
     * claim run rather than argued.
     */
    @Test
    fun `a source out of toSource completes normally rather than with the failure wrapper`() {
        val elements = Stream.from(rows)
            .filter { row -> row.customer != null }
            .toSource()
            .runWith(Sink.seq(), pekko.system)
            .toCompletableFuture()
            .join()

        elements shouldBe listOf(Row(1, "ada"), Row(3, "grace"))
    }
}
