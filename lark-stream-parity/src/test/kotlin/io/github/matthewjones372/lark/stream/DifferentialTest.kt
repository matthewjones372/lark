package io.github.matthewjones372.lark.stream

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.actor.ActorSystem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The operators whose edges are easy to get subtly wrong, run over a grid of their arguments and of
 * stream lengths, with Pekko's answer as the one every other backend has to give.
 */
class DifferentialTest {

    companion object {
        private val system: ActorSystem = ActorSystem.create("lark-stream-differential-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
            system.getWhenTerminated().toCompletableFuture().join()
        }

        private const val SETTLE_SECONDS = 10L
    }

    private val pekko = PekkoStreams(system)
    private val others: List<StreamBackend> = listOf(Forks(), TestStreams())

    private fun <R : Any> Run<*, R>.on(backend: StreamBackend): Exit<*, R> =
        run(backend).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    private fun <R : Any> agree(what: String, run: () -> Run<*, R>) {
        val expected = run().on(pekko)
        others.forEach { backend ->
            withClue("$what on ${backend.key}") { run().on(backend) shouldBe expected }
        }
    }

    @Test
    fun `sliding answers as Pekko's does, for every window, step and length`() {
        assertSoftly {
            for (n in 1..4) {
                for (step in 1..6) {
                    for (length in 0..13) {
                        agree("sliding($n, $step) over $length") {
                            Stream.from(1..length).sliding(n, step).runCollect()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `interleave answers as Pekko's does, for every segment and both lengths`() {
        assertSoftly {
            for (segment in 1..3) {
                for (left in 0..7) {
                    for (right in 0..7) {
                        agree("interleave($segment) of $left and $right") {
                            Stream.from(1..left).interleave(Stream.from(101..(100 + right)), segment).runCollect()
                        }
                    }
                }
            }
        }
    }
}
