package io.github.matthewjones372.lark.stream

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** A run on lark's own forks: one pull loop, on one virtual thread, and the same exits Pekko answers with. */
class ForksTest {

    @Test
    fun `on real time, a window closes with what it held while upstream is blocked, and a full group goes at once`() {
        val given = LinkedBlockingQueue<Int>(listOf(1, 2))
        val done = -1
        val exit = Stream.blocking(
            open = { given },
            next = { queue -> queue.take().takeIf { it != done } },
            wake = { queue -> queue.put(done) },
            close = { },
        )
            .groupedWithin(3, 50.milliseconds)
            // The first window can only close on time, with upstream blocked; what follows fills a group of three.
            .map { group -> group.also { if (it == listOf(1, 2)) given.addAll(listOf(3, 4, 5, done)) } }
            .runCollect()
            .run(Forks()).toCompletableFuture().get(10, TimeUnit.SECONDS)

        exit shouldBe Exit.Done(listOf(listOf(1, 2), listOf(3, 4, 5)))
    }

    @Test
    fun `on real time, groupedWithin hands on every element in order, a group at a time`() {
        val exit = Stream.from(1..10_000).groupedWithin(100, 1.minutes).runCollect()
            .run(Forks()).toCompletableFuture().get(10, TimeUnit.SECONDS)

        val groups = exit.shouldBeInstanceOf<Exit.Done<List<List<Int>>>>().value
        groups.flatten() shouldBe (1..10_000).toList()
        groups.all { it.size == 100 } shouldBe true
    }

    private data class Odd(val value: Int)

    private val forks = Forks()

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(): Exit<E, List<A>> = runCollect().run(forks).settled()

    private val naturals: Iterable<Int> = generateSequence(1) { it + 1 }.asIterable()

    @Test
    fun `a raise in an element body ends the run Failed with what it raised`() {
        val exit = Stream.of(1, 2, 3).mapOrFail { if (it == 2) raise(Odd(it)) else it }.collected()

        exit shouldBe Exit.Failed(Odd(2))
    }

    @Test
    fun `a bind on a Left in an element body is the same failure`() {
        val lookup: (Int) -> Either<Odd, Int> = { if (it % 2 == 0) Odd(it).left() else it.right() }

        val exit = Stream.of(1, 3, 4, 5).mapOrFail { lookup(it).bind() }.collected()

        exit shouldBe Exit.Failed(Odd(4))
    }

    @Test
    fun `take ends an infinite stream, and the source is pulled no further than it asked`() {
        val pulled = AtomicInteger()

        val exit = Stream.from(naturals).map { pulled.incrementAndGet() }.take(3).collected()

        exit shouldBe Exit.Done(listOf(1, 2, 3))
        pulled.get() shouldBe 3
    }

    @Test
    fun `every stage runs on a virtual thread that is not the caller's`() {
        val caller = Thread.currentThread()

        val exit = Stream.of(1).map { Thread.currentThread() }.collected()

        val ran = exit.shouldBeInstanceOf<Exit.Done<List<Thread>>>().value.single()
        withClue("the pull loop runs on its own fork") { (ran === caller) shouldBe false }
        ran.isVirtual shouldBe true
    }

    @Test
    fun `a defect ends the run Died, naming the operator, the element and the line that wrote it`() {
        val exit = Stream.of(1, 2).map { n -> check(n < 2) { "too big" }; n }.collected()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        died.cause.shouldBeInstanceOf<IllegalStateException>()
        died.cause.suppressed.single().message shouldContain "map died on 2, built at ForksTest.kt:"
    }

    @Test
    fun `another backend's own value is refused by name before anything runs`() {
        val pulled = AtomicInteger()
        val elsewhere =
            Stream<Nothing, Int>(Node.Native(Any(), BackendKey("Elsewhere"), "Elsewhere.source", "Here.kt:1"))

        val exit = Stream.from(naturals).map { pulled.incrementAndGet() }.merge(elsewhere).collected()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        died.cause.message shouldContain "Elsewhere.source, built at Here.kt:1, holds a Elsewhere value"
        pulled.get() shouldBe 0
    }

    @Test
    fun `stop ends a run that would not end, Done with what it had`() {
        val running = Stream.from(naturals).runFold(0) { count, _ -> count + 1 }.start(forks)

        running.close()

        running.exit.settled().shouldBeInstanceOf<Exit.Done<Int>>()
    }

    @Test
    fun `the bounds answer as their names say`() {
        Stream.of(1, 2, 3, 4, 5).drop(2).collected() shouldBe Exit.Done(listOf(3, 4, 5))
        Stream.of(1, 2, 3, 1).takeWhile { it < 3 }.collected() shouldBe Exit.Done(listOf(1, 2))
        Stream.of(1, 2, 3, 1).dropWhile { it < 3 }.collected() shouldBe Exit.Done(listOf(3, 1))
        Stream.of(1, 2, 3, 4).filterNot { it % 2 == 0 }.collected() shouldBe Exit.Done(listOf(1, 3))
        Stream.of(1, 2).drop(5).collected() shouldBe Exit.Done(emptyList())
    }

    @Test
    fun `grouping, scanning and state carry across elements`() {
        Stream.of(1, 2, 3, 4, 5).grouped(2).collected() shouldBe
            Exit.Done(listOf(listOf(1, 2), listOf(3, 4), listOf(5)))
        Stream.of(1, 2, 3).scan(0) { total, n -> total + n }.collected() shouldBe Exit.Done(listOf(0, 1, 3, 6))
        Stream.of(1, 2, 3)
            .statefulMap({ 0 }, { seen, n -> (seen + 1) to "$n@$seen" }, { seen -> "end@$seen" })
            .collected() shouldBe Exit.Done(listOf("1@0", "2@1", "3@2", "end@3"))
        Stream.of(1, 2, 3).mapConcat { n -> List(n) { n } }.collected() shouldBe Exit.Done(listOf(1, 2, 2, 3, 3, 3))
    }

    @Test
    fun `the failure operators replace, recover and name as Pekko's do`() {
        val failing = Stream.of(1, 2).mapOrFail { if (it == 2) raise(Odd(it)) else it }

        failing.either().collected() shouldBe Exit.Done(listOf(1.right(), Odd(2).left()))
        failing.catchAll { Stream.of(9) }.collected() shouldBe Exit.Done(listOf(1, 9))
        failing.mapError { it.value }.collected() shouldBe Exit.Failed(2)
        Stream.of(1.right(), Odd(3).left(), 5.right()).absolve().collected() shouldBe Exit.Failed(Odd(3))
        Stream.empty().orFailIfEmpty(Odd(0)).collected() shouldBe Exit.Failed(Odd(0))
    }

    @Test
    fun `streams join end to end, pairwise, and one inside another`() {
        Stream.of(1, 2).concat(Stream.of(3)).collected() shouldBe Exit.Done(listOf(1, 2, 3))
        Stream.of(3).prepend(Stream.of(1, 2)).collected() shouldBe Exit.Done(listOf(1, 2, 3))
        Stream.of(1, 2, 3).zip(Stream.of("a", "b")).collected() shouldBe Exit.Done(listOf(1 to "a", 2 to "b"))
        Stream.of(1, 2, 3).flatMapConcat { n -> Stream.from(List(n) { n }) }.collected()
            .shouldBeInstanceOf<Exit.Done<List<Int>>>().value shouldContainExactly listOf(1, 2, 2, 3, 3, 3)
    }

    @Test
    fun `a stage's value is the one element, and null from it is the defect fromStage names`() {
        val stage: CompletionStage<String> = java.util.concurrent.CompletableFuture.completedFuture("ready")

        @Suppress("UNCHECKED_CAST")
        val nulled = java.util.concurrent.CompletableFuture.completedFuture(null) as CompletionStage<String>

        Stream.fromStage(stage).collected() shouldBe Exit.Done(listOf("ready"))
        Stream.fromStage(nulled, ifNull = Odd(0)).collected() shouldBe Exit.Failed(Odd(0))
    }

    @Test
    fun `mapPar answers in the order the elements came, with never more bodies at once than it was given`() {
        val running = AtomicInteger()
        val most = AtomicInteger()

        val exit = Stream.from(1..40)
            .mapPar(8) { n ->
                most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                Thread.sleep((41 - n).toLong() % 7)
                running.decrementAndGet()
                n * 10
            }
            .collected()

        exit shouldBe Exit.Done((1..40).map { it * 10 })
        withClue("at most eight at once, and more than one, or it was not parallel") {
            (most.get() in 2..8) shouldBe true
        }
    }

    @Test
    fun `a raise in one mapPar body ends the run Failed, and interrupts the bodies still running`() {
        val interrupted = AtomicInteger()

        val exit = Stream.from(1..8)
            .mapPar<Odd, Int, Int>(8) { n ->
                if (n == 1) {
                    Thread.sleep(50)
                    raise(Odd(n))
                }
                try {
                    Thread.sleep(60_000)
                } catch (stopped: InterruptedException) {
                    interrupted.incrementAndGet()
                    throw stopped
                }
                n
            }
            .collected()

        exit shouldBe Exit.Failed(Odd(1))
        withClue("the seven still sleeping were interrupted, and had stopped before the exit completed") {
            interrupted.get() shouldBe 7
        }
    }

    @Test
    fun `take after mapPar ends the run, and no body is left running behind it`() {
        val running = AtomicInteger()

        val exit = Stream.from(naturals)
            .mapPar(4) { n ->
                running.incrementAndGet()
                try {
                    if (n > 3) Thread.sleep(60_000)
                } finally {
                    running.decrementAndGet()
                }
                n
            }
            .take(3)
            .collected()

        exit shouldBe Exit.Done(listOf(1, 2, 3))
        running.get() shouldBe 0
    }

    @Test
    fun `a mapPar body that throws is the defect the run dies with, naming mapPar`() {
        val exit = Stream.of(1, 2).mapPar(2) { n -> check(n != 2) { "two" }; n }.collected()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        (listOf(died.cause) + died.cause.suppressed).joinToString { it.message.orEmpty() } shouldContain "mapPar"
    }

    @Test
    fun `mapAsync keeps no more of its stages in flight than it was given, and answers in order`() {
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val delayed = CompletableFuture.delayedExecutor(2, TimeUnit.MILLISECONDS)

        val exit = Stream.from(1..30)
            .mapAsync(3) { n ->
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                CompletableFuture.supplyAsync({ inFlight.decrementAndGet(); n }, delayed)
            }
            .collected()

        exit shouldBe Exit.Done((1..30).toList())
        withClue("at most three stages at once, and more than one") { (most.get() in 2..3) shouldBe true }
    }

    @Test
    fun `tick on Forks waits on real time, one element per interval`() {
        val started = System.nanoTime()

        val exit = Stream.tick(20.milliseconds, "t").take(3).collected()

        exit shouldBe Exit.Done(listOf("t", "t", "t"))
        withClue("three ticks at 20 ms take at least 60 ms") {
            ((System.nanoTime() - started) >= 60_000_000L) shouldBe true
        }
    }

    @Test
    fun `groupedWithin on Forks closes a window on real time while upstream is slow`() {
        val exit = Stream.from(1..6)
            .map { n ->
                Thread.sleep(30)
                n
            }
            .groupedWithin(100, 70.milliseconds)
            .collected()

        val groups = exit.shouldBeInstanceOf<Exit.Done<List<List<Int>>>>().value
        withClue("every element in order, in windows the clock closed rather than one full group: $groups") {
            groups.flatten() shouldBe (1..6).toList()
            (groups.size > 1) shouldBe true
        }
    }

    @Test
    fun `a restart on Forks waits its delay on real time, and runs the description again`() {
        val attempts = AtomicInteger()
        val started = System.nanoTime()

        val exit = Stream.of(1, 2)
            .map { n ->
                check(!(n == 2 && attempts.incrementAndGet() == 1)) { "first time" }
                n
            }
            .restartOnDefect(io.github.matthewjones372.lark.Schedule.spaced(50.milliseconds))
            .collected()

        exit shouldBe Exit.Done(listOf(1, 1, 2))
        ((System.nanoTime() - started) >= 50_000_000L) shouldBe true
    }
}
