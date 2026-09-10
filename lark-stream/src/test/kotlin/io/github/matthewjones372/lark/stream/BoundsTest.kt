package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage

/** What the bounding operators promise, asserted on a real system. */
class BoundsTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-bounds-test")
    }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private val ids = listOf(1, 2, 3, 4, 5)

    @Test
    fun `take ends the stream after the count it was given`() {
        Stream.from(ids).take(2).collected() shouldBe Exit.Done(listOf(1, 2))
    }

    @Test
    fun `drop begins the stream after the count it was given`() {
        Stream.from(ids).drop(3).collected() shouldBe Exit.Done(listOf(4, 5))
    }

    @Test
    fun `takeWhile ends where the predicate first answers false`() {
        Stream.from(ids).takeWhile { it < 3 }.collected() shouldBe Exit.Done(listOf(1, 2))
    }

    @Test
    fun `dropWhile begins where the predicate first answers false, that element included`() {
        Stream.from(ids).dropWhile { it < 3 }.collected() shouldBe Exit.Done(listOf(3, 4, 5))
    }

    @Test
    fun `filterNot keeps what filter would have dropped`() {
        Stream.from(ids).filterNot { it % 2 == 0 }.collected() shouldBe Exit.Done(listOf(1, 3, 5))
    }

    @Test
    fun `a bound is a pipe as well as a stream`() {
        val firstTwo: Pipe<Nothing, Int, Int> = Pipe.take(2)

        Stream.from(ids).via(firstTwo).collected() shouldBe Exit.Done(listOf(1, 2))
    }

    @Test
    fun `a throw in a predicate dies naming the operator`() {
        val died = Stream.from(ids)
            .takeWhile { error("the predicate nobody declared") }
            .collected()
            .shouldBeInstanceOf<Exit.Died>()

        withClue("the operator and the element are what a reader needs to find it") {
            died.cause.suppressed.single().message.orEmpty() shouldContain "takeWhile died on 1"
        }
    }
}
