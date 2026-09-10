package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage

/** Two feeds as one, and what their failure types do when they meet. */
class FaninTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-fanin-test")
    }

    private sealed interface FeedError

    private data class HttpDown(val why: String) : FeedError

    private data class KafkaLag(val behind: Int) : FeedError

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private val http: Stream<HttpDown, Int> = Stream.from(listOf(1, 2))

    private val kafka: Stream<KafkaLag, Int> = Stream.from(listOf(3, 4))

    @Test
    fun `two feeds that fail differently merge under the failure they share`() {
        val merged: Stream<FeedError, Int> = http.merge(kafka)

        withClue("no mapLeft on either side, and no Either in the element") {
            merged.collected().shouldBeInstanceOf<Exit.Done<List<Int>>>().value shouldContainExactlyInAnyOrder
                listOf(1, 2, 3, 4)
        }
    }

    @Test
    fun `a failure on either side ends the run`() {
        val failing: Stream<KafkaLag, Int> = Stream.from(listOf(9)).mapOrFail { fail(KafkaLag(7)) }

        val merged: Stream<FeedError, Int> = http.merge(failing)

        merged.collected() shouldBe Exit.Failed(KafkaLag(7))
    }

    @Test
    fun `mergeAll takes as many as there are`() {
        val merged: Stream<FeedError, Int> = http.mergeAll(kafka, Stream.from(listOf(5)))

        merged.collected().shouldBeInstanceOf<Exit.Done<List<Int>>>().value shouldContainExactlyInAnyOrder
            listOf(1, 2, 3, 4, 5)
    }

    @Test
    fun `interleave takes a segment from each in turn`() {
        val interleaved: Stream<FeedError, Int> = http.interleave(kafka, 1)

        interleaved.collected() shouldBe Exit.Done(listOf(1, 3, 2, 4))
    }

    @Test
    fun `zip answers a Kotlin Pair, which destructures`() {
        val zipped: Stream<FeedError, String> = http.zip(kafka).map { (left, right) -> "$left-$right" }

        zipped.collected() shouldBe Exit.Done(listOf("1-3", "2-4"))
    }

    @Test
    fun `a throw in a zipWith body dies naming the operator`() {
        val zipped: Stream<FeedError, String> = http.zipWith(kafka) { _, _ -> error("the body nobody declared") }

        val died = zipped.collected().shouldBeInstanceOf<Exit.Died>()

        died.cause.suppressed.single().message.orEmpty() shouldContain "zipWith died on 3"
    }
}
