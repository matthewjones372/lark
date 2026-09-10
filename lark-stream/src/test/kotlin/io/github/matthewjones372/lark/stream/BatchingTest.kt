package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.javadsl.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue

/** Batching, buffering, running state, and the taps beside a pipeline. */
class BatchingTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-batching-test")
    }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private val ids = listOf(1, 2, 3, 4, 5)

    /** Declared so the step's types are known: a body that only throws infers nothing. */
    private fun failingStep(): Pair<Int, Int> = error("the step nobody declared")

    @Test
    fun `grouped answers batches, the last one short`() {
        Stream.from(ids).grouped(2).collected() shouldBe
            Exit.Done(listOf(listOf(1, 2), listOf(3, 4), listOf(5)))
    }

    @Test
    fun `sliding answers a window moved on by its step`() {
        Stream.from(ids).sliding(2, 2).collected() shouldBe
            Exit.Done(listOf(listOf(1, 2), listOf(3, 4), listOf(5)))
    }

    @Test
    fun `groupedWithin answers whatever it has when the window closes`() {
        val batched = Stream.from(ids).groupedWithin(10, Duration.ofMillis(50)).collected()

        withClue("ten never arrive, so the window is what ends the batch") {
            batched shouldBe Exit.Done(listOf(ids))
        }
    }

    @Test
    fun `a buffer leaves the elements alone`() {
        Stream.from(ids).buffer(2, OverflowStrategy.backpressure()).collected() shouldBe Exit.Done(ids)
    }

    @Test
    fun `scan emits the zero and then a total per element`() {
        Stream.from(ids).scan(0) { total, id -> total + id }.collected() shouldBe
            Exit.Done(listOf(0, 1, 3, 6, 10, 15))
    }

    @Test
    fun `a throw while carrying state dies naming scan and the element`() {
        val died = Stream.from(ids)
            .scan(0) { _, _ -> error("the fold nobody declared") }
            .collected()
            .shouldBeInstanceOf<Exit.Died>()

        died.cause.suppressed.single().message.orEmpty() shouldContain "scan died on 1"
    }

    @Test
    fun `statefulMap carries state and may owe one last element`() {
        val numbered = Stream.from(listOf("a", "b", "c"))
            .statefulMap({ 0 }, { seen, s -> (seen + 1) to "$seen:$s" }, { seen -> "seen $seen" })
            .collected()

        numbered shouldBe Exit.Done(listOf("0:a", "1:b", "2:c", "seen 3"))
    }

    @Test
    fun `a throw in statefulMap dies naming the element`() {
        val died = Stream.from(ids)
            .statefulMap({ 0 }, { _, _ -> failingStep() })
            .collected()
            .shouldBeInstanceOf<Exit.Died>()

        died.cause.suppressed.single().message.orEmpty() shouldContain "statefulMap died on 1"
    }

    @Test
    fun `alsoTo sees every element, and the pipeline is unchanged`() {
        val audited = ConcurrentLinkedQueue<Int>()

        val exit = Stream.from(ids).alsoTo(Sink.foreach { audited.add(it) }).collected()

        exit shouldBe Exit.Done(ids)
        audited.toList() shouldBe ids
    }

    @Test
    fun `wireTap leaves the pipeline alone`() {
        val exit = Stream.from(ids).wireTap(Sink.ignore()).collected()

        exit shouldBe Exit.Done(ids)
    }
}
