package io.github.matthewjones372.lark.stream.actors

import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.stream.Actors
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamSpi
import io.github.matthewjones372.lark.stream.blocking
import io.github.matthewjones372.lark.stream.filter
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runCollect
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

/** A stream with no end: a run of it ends only when it is stopped. */
private fun endless() = Stream.from(Iterable { generateSequence(1) { it + 1 }.iterator() })

@OptIn(StreamSpi::class)
class ActorsTest {

    @Test
    fun `a run pulls its whole chain on an actor, in batches, and ends with the answer`() {
        val exit = flock<Nothing, Any> {
            Stream.from(1..1_000).map { it * 2 }.filter { it % 3 == 0 }.runFold(0) { sum, n -> sum + n }
                .run(Actors(this, batch = 7)).toCompletableFuture().join()
        }

        exit shouldBe Exit.Done((1..1_000).map { it * 2 }.filter { it % 3 == 0 }.sum()).right()
    }

    @Test
    fun `a declared failure ends the run Failed, and a throw ends it Died`() {
        val exits = flock<Nothing, Pair<Any, Any>> {
            val actors = Actors(this)
            val failed = Stream.from(1..10).mapOrFail { if (it == 5) fail("five") else it }.runCollect()
                .run(actors).toCompletableFuture().join()
            val died = Stream.from(1..10).map { check(it < 5) { "too big" } }.runCollect()
                .run(actors).toCompletableFuture().join()
            failed to died
        }

        exits.getOrNull()!!.first shouldBe Exit.Failed("five")
        exits.getOrNull()!!.second.shouldBeInstanceOf<Exit.Died>()
    }

    @Test
    fun `runs without end share the runners, so a short run started after them still ends`() {
        val answer = flock<Nothing, Any> {
            val actors = Actors(this)
            val endless = (1..Runtime.getRuntime().availableProcessors() * 4).map {
                endless().runCollect().start(actors)
            }
            val short = Stream.from(1..3).runCollect().run(actors).toCompletableFuture().join()
            endless.forEach { it.close() }
            short to actors.running
        }

        answer shouldBe (Exit.Done(listOf(1, 2, 3)) to 0).right()
    }

    @Test
    fun `a stop wakes a source it is blocked in, and the run is Done with what it had`() {
        val answer = flock<Nothing, Any> {
            val actors = Actors(this)
            val given = LinkedBlockingQueue<Int>()
            val taken = CountDownLatch(2)
            val running = Stream.blocking(
                open = { given },
                next = { queue -> queue.take().also { taken.countDown() }.takeIf { it > 0 } },
                wake = { queue -> queue.put(0) },
                close = { },
            ).runCollect().start(actors)
            given.put(1)
            given.put(2)
            taken.await()

            running.close()
            running.exit.toCompletableFuture().join() to actors.running
        }

        answer shouldBe (Exit.Done(listOf(1, 2)) to 0).right()
    }

    @Test
    fun `a batch of no elements is refused`() {
        flock<Nothing, Unit> {
            shouldThrow<IllegalArgumentException> { Actors(this, batch = 0) }
        }
    }
}
