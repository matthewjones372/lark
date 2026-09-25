package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.BoundedSourceQueue
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Keep
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.toJavaDuration

/** The way in from Pekko whatever a source materialises, and the ticker that needs no way in. */
class SourcesTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-sources-test")

        private val fast: Duration = 10.milliseconds
    }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /**
     * Three and no more. A ticker ends where the sink stops asking, so the count is on the sink side
     * rather than in an operator this library does not have.
     */
    private fun <A : Any> firstThree(): Sink<A, CompletionStage<List<A>>> =
        Flow.create<A>().take(3).toMat(Sink.seq(), Keep.right())

    @Test
    fun `tick is a stream of its own, with no materialised value for a caller to unwrap`() {
        val exit = Stream.tick(every = fast, element = "poll").runWith(firstThree()).run(pekko.system).settled()

        exit shouldBe Exit.Done(listOf("poll", "poll", "poll"))
    }

    @Test
    fun `tick waits the delay it names before the first element`() {
        val after = 300.milliseconds
        val started = System.nanoTime()

        val exit = Stream.tick(every = fast, element = "poll", after = after)
            .runWith(Sink.head<String>())
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done("poll")
        withClue("the first element cannot arrive before the delay the call named") {
            (System.nanoTime() - started).nanoseconds shouldBeGreaterThanOrEqualTo after
        }
    }

    @Test
    fun `from takes a ticker, which materialises a Cancellable`() {
        val ticks: Source<String, Cancellable> = Source.tick(fast.toJavaDuration(), fast.toJavaDuration(), "poll")

        val exit = Stream.from(ticks).runWith(firstThree()).run(pekko.system).settled()

        exit shouldBe Exit.Done(listOf("poll", "poll", "poll"))
    }

    /**
     * Nothing can offer to a queue whose materialised value was dropped, so what the run claims is
     * that the source is taken and materialises: the element read comes from ahead of it.
     */
    @Test
    fun `from takes a queue, which materialises the handle it is fed through`() {
        val queued: Source<Int, BoundedSourceQueue<Int>> = Source.queue(4)

        val exit = Stream.from(queued)
            .prepend(Stream.single(1))
            .runWith(Sink.head<Int>())
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(1)
    }

    @Test
    fun `a source that materialises NotUsed reads as it always did`() {
        val plain: Source<Int, NotUsed> = Source.from(listOf(1, 2, 3))

        val exit = Stream.from(plain).runCollect().run(pekko.system).settled()

        exit shouldBe Exit.Done(listOf(1, 2, 3))
    }
}
