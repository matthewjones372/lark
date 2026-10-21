package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage

/** An element that is itself a stream, flattened in order and out of it. */
class FlattenTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-flatten-test")
    }

    private data class FetchError(val cursor: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private fun page(cursor: Int): Stream<FetchError, String> =
        if (cursor == 2) Stream.from(listOf(1)).mapOrFail { fail(FetchError(cursor)) }
        else Stream.from(listOf("$cursor-a", "$cursor-b"))

    @Test
    fun `a stream of cursors becomes a stream of pages, in order`() {
        val pages = Stream.from(listOf(1, 3)).flatMapConcat { page(it) }.collected()

        withClue("no toSource and no Either in sight") {
            pages shouldBe Exit.Done(listOf("1-a", "1-b", "3-a", "3-b"))
        }
    }

    @Test
    fun `an inner failure is the outer stream's failure`() {
        Stream.from(listOf(1, 2, 3)).flatMapConcat { page(it) }.collected() shouldBe
            Exit.Failed(FetchError(2))
    }

    @Test
    fun `flatten is the same thing under the name a reader looks for`() {
        val nested: Stream<FetchError, Stream<FetchError, String>> =
            Stream.from(listOf(1, 3)).map { page(it) }

        nested.flatten().collected() shouldBe Exit.Done(listOf("1-a", "1-b", "3-a", "3-b"))
    }

    @Test
    fun `flatMapMerge answers every element, breadth deciding only the order`() {
        val merged = Stream.from(listOf(1, 3, 4)).flatMapMerge(4) { page(it) }.collected()

        merged.shouldBeInstanceOf<Exit.Done<List<String>>>().value shouldContainExactlyInAnyOrder
            listOf("1-a", "1-b", "3-a", "3-b", "4-a", "4-b")
    }

    @Test
    fun `a throw while building the inner stream dies naming the operator`() {
        val died = Stream.from(listOf(1))
            .flatMapConcat<FetchError, FetchError, Int, String> { error("the fetch nobody declared") }
            .collected()
            .shouldBeInstanceOf<Exit.Died>()

        died.cause.suppressed.single().message.orEmpty() shouldContain "flatMapConcat died on 1"
    }
}
