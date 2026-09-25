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
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

/** A run on lark's own forks: one pull loop, on one virtual thread, and the same exits Pekko answers with. */
class ForksTest {

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
    fun `an operator that needs a second thread is refused by name before anything runs`() {
        val pulled = AtomicInteger()

        val exit = Stream.from(naturals).map { pulled.incrementAndGet() }.mapPar(2) { it }.collected()

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        died.cause.message shouldContain "mapPar, built at ForksTest.kt:"
        died.cause.message shouldContain ", is not something Forks runs"
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
}
