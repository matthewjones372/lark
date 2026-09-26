package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.apache.pekko.actor.ActorSystem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A buffer on every backend: upstream runs ahead of a slow reader, and only as far as the buffer lets it. */
class BufferTest {

    companion object {
        private val system: ActorSystem = ActorSystem.create("lark-stream-buffer-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
            system.getWhenTerminated().toCompletableFuture().join()
        }

        private const val SIZE = 4
        private const val SETTLE_SECONDS = 10L
    }

    /**
     * How far past what was read each backend lets upstream get: the buffer, and the one element upstream
     * holds while it waits for room. Pekko's async boundary has an input buffer of its own on top.
     */
    private val backends: List<Pair<StreamBackend, Int>> =
        listOf(
            PekkoStreams(system) to SIZE + 1 + PEKKO_INPUT_BUFFER, Forks() to SIZE + 1, TestStreams() to SIZE + 1,
            Actors(heldFlock) to SIZE + 1,
        )

    @TestFactory
    fun `a slow reader lets upstream run ahead by the buffer, and no further`(): List<DynamicTest> =
        backends.map { (backend, furthest) ->
            dynamicTest(backend.key.name) {
                val pulled = AtomicInteger()
                val aheadWhileReading = AtomicInteger()

                val exit = Stream.from(generateSequence(1) { it + 1 }.asIterable())
                    .map { pulled.incrementAndGet() }
                    .buffer(SIZE)
                    .map { n ->
                        if (n == 1) {
                            Thread.sleep(300)
                            aheadWhileReading.set(pulled.get() - n)
                        }
                        n
                    }
                    .take(1)
                    .runCollect()
                    .run(backend).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit shouldBe Exit.Done(listOf(1))
                withClue("upstream kept going while the first element was read: ${aheadWhileReading.get()} ahead") {
                    aheadWhileReading.get() shouldBeGreaterThan 0
                    aheadWhileReading.get() shouldBeLessThanOrEqual furthest
                }
            }
        }
}

/** Pekko's default input buffer on an async boundary, `pekko.stream.materializer.max-input-buffer-size`. */
private const val PEKKO_INPUT_BUFFER = 16
