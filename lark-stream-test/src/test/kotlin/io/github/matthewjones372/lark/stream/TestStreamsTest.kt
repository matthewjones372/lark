package io.github.matthewjones372.lark.stream

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** A run on TestStreams is over by the time `run` returns, and it ran where the test did. */
class TestStreamsTest {

    private data class Odd(val value: Int)

    @Test
    fun `every stage runs on the calling thread, and the exit is complete when run returns`() {
        val caller = Thread.currentThread()

        val exit = Stream.of(1, 2, 3).map { Thread.currentThread() }.runCollect().run(TestStreams())

        exit.toCompletableFuture().isDone shouldBe true
        exit.toCompletableFuture().join().shouldBeInstanceOf<Exit.Done<List<Thread>>>().value.toSet() shouldBe
            setOf(caller)
    }

    @Test
    fun `a declared failure ends the run Failed, as on any backend`() {
        val exit = Stream.of(1, 2, 3).mapOrFail { if (it == 2) raise(Odd(it)) else it }.runCollect()
            .run(TestStreams())

        exit.toCompletableFuture().join() shouldBe Exit.Failed(Odd(2))
    }

    @Test
    fun `an operator it cannot run is refused by its own name`() {
        val exit = Stream.of(1, 2).mapPar(2) { it }.runCollect().run(TestStreams())

        exit.toCompletableFuture().join().shouldBeInstanceOf<Exit.Died>().cause.message shouldContain
            ", is not something TestStreams runs"
    }
}
