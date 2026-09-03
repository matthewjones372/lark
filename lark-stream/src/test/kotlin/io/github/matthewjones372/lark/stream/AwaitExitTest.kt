package io.github.matthewjones372.lark.stream

import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

/** The one fold of an `Exit` into a `Raise`: what each case does to the scope waiting on it. */
class AwaitExitTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-awaitexit-test")
    }

    private data class Declined(val id: Int)

    private fun completed(exit: Exit<Declined, Int>): CompletableFuture<Exit<Declined, Int>> =
        CompletableFuture.completedFuture(exit)

    @Test
    fun `Done is the value the run folded to`() {
        either { awaitExit(completed(Exit.Done(7))) } shouldBe 7.right()
    }

    @Test
    fun `Failed raises the error the run ended with`() {
        either { awaitExit(completed(Exit.Failed(Declined(2)))) } shouldBe Declined(2).left()
    }

    @Test
    fun `Died throws the cause nobody declared`() {
        val cause = IllegalStateException("no ledger")

        val thrown = shouldThrow<IllegalStateException> {
            either { awaitExit(completed(Exit.Died(cause))) }
        }

        withClue("a defect is not a failure a Raise can name, so it stays a throw") {
            thrown shouldBeSameInstanceAs cause
        }
    }

    /** The pipeline the spec draws: a run, folded where the handler that started it can read it. */
    @Test
    fun `a run awaited in a Raise gives back what it folded`() {
        val counted = either<Declined, Int> {
            awaitExit(
                Stream.from(listOf(1, 2, 3))
                    .map { id -> "receipt-$id" }
                    .runFold(0) { n, _ -> n + 1 }
                    .run(pekko.system),
            )
        }

        counted shouldBe 3.right()
    }

    @Test
    fun `a declared failure in the run raises where the run was awaited`() {
        val counted = either<Declined, Int> {
            awaitExit(
                Stream.from(listOf(1, 2, 3))
                    .mapPar(2) { id -> if (id == 2) raise(Declined(id)) else "receipt-$id" }
                    .runFold(0) { n, _ -> n + 1 }
                    .run(pekko.system),
            )
        }

        counted shouldBe Declined(2).left()
    }

    /** The wait is `lark-pekko`'s, so the bridge that cancels an abandoned stage is the same code. */
    @Test
    fun `a fork interrupted while awaiting a run cancels the stage`() {
        val stage = CompletableFuture<Exit<Declined, Int>>()
        val awaiting = CountDownLatch(1)

        shouldThrow<InterruptedException> {
            flock<Declined, String> {
                async {
                    awaiting.countDown()
                    awaitExit(stage)
                }
                awaiting.await()
                "the scope closes with the fork still waiting"
            }
        }

        stage.isCancelled shouldBe true
        withClue("a cancelled stage takes no value, so a run answering late is dropped") {
            stage.complete(Exit.Done(1)) shouldBe false
        }
    }
}
