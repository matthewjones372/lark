package io.github.matthewjones372.lark.pekko

import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.apache.pekko.dispatch.Futures
import org.junit.jupiter.api.Test
import scala.concurrent.Future
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

private data class Bad(val why: String)

private class Boom : RuntimeException("boom")

class AwaitTest {

    @Test
    fun `a completed stage answers with its value`() {
        CompletableFuture.completedFuture(42).await() shouldBe 42
    }

    @Test
    fun `a stage that failed rethrows its cause, as the same instance`() {
        val boom = Boom()
        val failed = CompletableFuture<Int>()
        failed.completeExceptionally(boom)

        val thrown = shouldThrow<Boom> { failed.await() }

        withClue("the caller declared Boom, not the ExecutionException the wait wrapped it in") {
            thrown shouldBeSameInstanceAs boom
        }
    }

    @Test
    fun `an Either awaited inside either raises where it is bound`() {
        either<Bad, Int> {
            CompletableFuture.completedFuture(21.right()).await().bind() * 2
        } shouldBe 42.right()

        either<Bad, Int> {
            CompletableFuture.completedFuture(Bad("upstream").left()).await().bind()
        } shouldBe Bad("upstream").left()
    }

    @Test
    fun `a fork interrupted while awaiting cancels the stage, and its late value is dropped`() {
        val stage = CompletableFuture<String>()
        val awaiting = CountDownLatch(1)

        shouldThrow<InterruptedException> {
            flock<Bad, String> {
                async {
                    awaiting.countDown()
                    stage.await()
                }
                awaiting.await()
                "the scope closes with the fork still waiting"
            }
        }

        stage.isCancelled shouldBe true
        withClue("a cancelled stage takes no value, so one arriving late is dropped") {
            stage.complete("late") shouldBe false
        }
    }

    @Test
    fun `a Scala future round-trips, value and failure`() {
        Future.successful(42).await() shouldBe 42

        val boom = Boom()
        // Pekko's Java API for a failed future: `Future.failed` is an instance method of the trait too,
        // so the companion's has no static forwarder for Kotlin to call.
        val thrown = shouldThrow<Boom> { Futures.failed<Int>(boom).await() }

        thrown shouldBeSameInstanceAs boom
    }
}
