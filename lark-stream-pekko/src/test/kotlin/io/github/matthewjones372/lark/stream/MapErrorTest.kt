package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage

/** A declared failure said again in another vocabulary, without saying recovery. */
class MapErrorTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-map-error-test")
    }

    private data class Declined(val id: Int)

    private data class IngestError(val why: String)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private fun declining(): Stream<Declined, Int> =
        Stream.from(listOf(1, 2, 3)).mapOrFail { id -> if (id == 2) fail(Declined(id)) else id }

    @Test
    fun `a failure leaves as the error it was mapped to`() {
        val exit = declining().mapError { IngestError("declined ${it.id}") }.collected()

        exit shouldBe Exit.Failed(IngestError("declined 2"))
    }

    @Test
    fun `a stream that does not fail is untouched`() {
        val exit = Stream.from(listOf(1, 2)).mapError { IngestError("never") }.collected()

        exit shouldBe Exit.Done(listOf(1, 2))
    }

    @Test
    fun `a throw while mapping the failure dies naming the operator`() {
        val died = declining()
            .mapError<Declined, IngestError, Int> { error("the mapping nobody declared") }
            .collected()
            .shouldBeInstanceOf<Exit.Died>()

        withClue("a defect in the mapping is not the failure it was mapping") {
            died.cause.suppressed.single().message.orEmpty() shouldContain "mapError died on Declined(id=2)"
        }
    }
}
