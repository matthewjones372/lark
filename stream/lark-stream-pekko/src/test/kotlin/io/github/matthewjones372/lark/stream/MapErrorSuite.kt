package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.withClue
import java.util.concurrent.CompletionStage

/** A declared failure said again in another vocabulary, without saying recovery. */
object MapErrorSuite : Suite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("lark-stream-map-error-test")

    private data class Declined(val id: Int)

    private data class IngestError(val why: String)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(pekko: PekkoSystem): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private fun declining(): Stream<Declined, Int> =
        Stream.from(listOf(1, 2, 3)).mapOrFail { id -> if (id == 2) fail(Declined(id)) else id }

    init {
        test("a failure leaves as the error it was mapped to") { pekko ->
            val exit = declining().mapError { IngestError("declined ${it.id}") }.collected(pekko)

            exit shouldBe Exit.Failed(IngestError("declined 2"))
        }

        test("a stream that does not fail is untouched") { pekko ->
            val exit = Stream.from(listOf(1, 2)).mapError { IngestError("never") }.collected(pekko)

            exit shouldBe Exit.Done(listOf(1, 2))
        }

        test("a throw while mapping the failure dies naming the operator") { pekko ->
            declining()
                .mapError<Declined, IngestError, Int> { error("the mapping nobody declared") }
                .collected(pekko)
                .shouldBeInstanceOf<Exit.Died> { died ->
                    withClue("a defect in the mapping is not the failure it was mapping") {
                        died.cause.suppressed.single().message.orEmpty() shouldContain "mapError died on Declined(id=2)"
                    }
                }
        }
    }
}
