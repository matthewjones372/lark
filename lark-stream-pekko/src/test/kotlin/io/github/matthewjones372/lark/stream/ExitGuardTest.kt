package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.pekko.stream.javadsl.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** The guard `Stream.fromStage` has, at the other end of the pipeline. */
class ExitGuardTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-exit-guard-test")
    }

    /** A sink whose materialised stage completes with null, whatever its type argument says. */
    @Suppress("UNCHECKED_CAST")
    private fun lying(): Sink<Int, CompletionStage<String>> =
        Sink.ignore<Int>().mapMaterializedValue {
            CompletableFuture.completedFuture<String?>(null) as CompletionStage<String>
        }

    @Test
    fun `a sink that materialises null is Died rather than Done of null`() {
        val exit = Stream.from(listOf(1, 2)).runWith(lying()).run(pekko.system).toCompletableFuture().join()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        withClue("Done(null) would hand a null into a slot Kotlin believes is not nullable") {
            died.cause.shouldBeInstanceOf<NullPointerException>()
            died.cause.message.orEmpty() shouldContain "run ended with null"
        }
    }

    @Test
    fun `a sink that materialises a value is Done`() {
        val exit = Stream.from(listOf(1, 2)).runCollect().run(pekko.system).toCompletableFuture().join()

        exit shouldBe Exit.Done(listOf(1, 2))
    }
}
