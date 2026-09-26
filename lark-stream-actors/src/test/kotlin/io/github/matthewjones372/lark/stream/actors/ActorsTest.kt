package io.github.matthewjones372.lark.stream.actors

import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.stream.Actors
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamSpi
import io.github.matthewjones372.lark.stream.blocking
import io.github.matthewjones372.lark.stream.buffer
import io.github.matthewjones372.lark.stream.filter
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.mapPar
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runCollect
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.start
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun `mapPar runs its bodies on its workers, never more at once than it allows, and answers in order`() {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        // Three bodies meet at the barrier each time, so a mapPar(3) that ran them one at a time would never pass it.
        val together = CyclicBarrier(3)
        val exit = flock<Nothing, Any> {
            Stream.from(1..9).mapPar(3) { n ->
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                together.await(1, TimeUnit.MINUTES)
                inFlight.decrementAndGet()
                n * 10
            }.runCollect().run(Actors(this)).toCompletableFuture().join()
        }

        exit shouldBe Exit.Done((1..9).map { it * 10 }).right()
        most.get() shouldBe 3
    }

    @Test
    fun `a mapPar body that raises ends the run Failed, and one that throws ends it Died`() {
        val exits = flock<Nothing, Pair<Any, Any>> {
            val actors = Actors(this)
            val numbers: Stream<String, Int> = Stream.from(1..10)
            val failed = numbers.mapPar(2) { if (it == 4) raise("four") else it }.runCollect()
                .run(actors).toCompletableFuture().join()
            val died = Stream.from(1..10).mapPar(2) { check(it < 4) { "too big" } }.runCollect()
                .run(actors).toCompletableFuture().join()
            failed to died
        }

        exits.getOrNull()!!.first shouldBe Exit.Failed("four")
        exits.getOrNull()!!.second.shouldBeInstanceOf<Exit.Died>()
    }

    @Test
    fun `a buffer hands on every element in order, and what upstream threw after what came before it`() {
        val exits = flock<Nothing, Pair<Any, Any>> {
            val actors = Actors(this, batch = 2)
            val all = Stream.from(1..100).buffer(3).runCollect().run(actors).toCompletableFuture().join()
            val threw = Stream.from(1..10).map { check(it < 6) { "six" } }.buffer(3).runCollect()
                .run(actors).toCompletableFuture().join()
            all to threw
        }

        exits.getOrNull()!!.first shouldBe Exit.Done((1..100).toList())
        exits.getOrNull()!!.second.shouldBeInstanceOf<Exit.Died>().cause.message shouldBe "six"
    }

    @Test
    fun `mapPar and buffer run on the run's child actors, and start nothing on its executor`() {
        val started = AtomicInteger()
        val counting = Executor { task ->
            started.incrementAndGet()
            Thread.ofVirtual().start(task)
        }
        val exit = flock<Nothing, Any> {
            Stream.from(1..50).mapPar(4) { it + 1 }.buffer(8).runCollect()
                .run(Actors(this, on = counting)).toCompletableFuture().join()
        }

        exit shouldBe Exit.Done((2..51).toList()).right()
        started.get() shouldBe 0
    }

    @Test
    fun `a batch of no elements is refused`() {
        flock<Nothing, Unit> {
            shouldThrow<IllegalArgumentException> { Actors(this, batch = 0) }
        }
    }
}
