package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.stream.javadsl.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** The word a caller says about a source lark did not build, asserted on a real system. */
class OrFailIfEmptyTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-or-fail-if-empty-test")
    }

    private data class Missing(val id: Int)

    private data class NoCustomer(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    @Test
    fun `a source that ends having emitted nothing fails with the error the caller named`() {
        val exit = Stream.from(Source.empty<String>())
            .orFailIfEmpty(Missing(7))
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Missing(7))
    }

    @Test
    fun `a source that emitted carries the elements it emitted and nothing else`() {
        val exit = Stream.from(Source.from(listOf("ada", "grace")))
            .orFailIfEmpty(Missing(7))
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf("ada", "grace"))
    }

    @Test
    fun `a stream that fails before emitting fails with its own error rather than the emptiness one`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> fail(NoCustomer(id)) }
            .orFailIfEmpty(Missing(7))
            .runCollect()
            .run(pekko.system)
            .settled()

        withClue("nothing was emitted, and the failure that stopped it is still the one to answer with") {
            exit shouldBe Exit.Failed(NoCustomer(1))
        }
    }

    @Test
    fun `a stream that fails after emitting is unchanged too`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> if (id == 2) fail(NoCustomer(id)) else id }
            .orFailIfEmpty(Missing(7))
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(NoCustomer(2))
    }

    /**
     * The case the spec is about, closed the other way: the stage completed with `null` and Pekko's
     * builder made an empty source of it, so the emptiness is what carries the name.
     */
    @Test
    fun `a source built from a stage that completed with null fails with the error named`() {
        val completedWithNull = CompletableFuture<String>().apply { complete(null) }

        val exit = Stream.from(Source.completionStage(completedWithNull))
            .orFailIfEmpty(Missing(7))
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(Missing(7))
    }
}
