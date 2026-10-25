package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration

/** The request a line written here would be annotated with, or "" when nothing bound one. */
private fun requestHere(): String = capturingLogs { logs ->
    logInfo("handling")
    logs.all().single().annotations["request_id"].orEmpty()
}

/** Spec 0198: a plain behaviour handed the messages waiting as one run, each with its own carried context. */
class BatchingTest {

    @Test
    fun `the messages already waiting are handed over as one run, in order`() {
        val seen = CopyOnWriteArrayList<List<Int>>()
        val opened = CountDownLatch(1)
        val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, run ->
            seen += run.toList()
            stay()
        }.onStart { opened.await() }
        flock<Nothing, Unit> {
            val actor = spawn("runs", runs)
            (1..3).forEach(actor::tell)
            opened.countDown()
            awaitIdle()
        }
        seen shouldBe listOf(listOf(1, 2, 3))
    }

    @Test
    fun `each message of a run is within its own sender's context, and the run within none`() {
        val inside = CopyOnWriteArrayList<String>()
        val outside = CopyOnWriteArrayList<String>()
        val opened = CountDownLatch(1)
        val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, run ->
            outside += requestHere()
            run.indices.forEach { i -> inside += run.within(i) { requestHere() } }
            stay()
        }.onStart { opened.await() }
        flock<Nothing, Unit> {
            val actor = spawn("runs", runs)
            logAnnotated("request_id" to "r-1") { actor.tell(1) }
            actor.tell(2)
            logAnnotated("request_id" to "r-3") { actor.tell(3) }
            opened.countDown()
            awaitIdle()
        }
        inside shouldBe listOf("r-1", "", "r-3")
        outside shouldBe listOf("")
    }

    @Test
    fun `a message handled alone is a run of one, in its own sender's context`() {
        val seen = CopyOnWriteArrayList<Pair<List<Int>, String>>()
        val runs = batching<Int, Unit>(Unit, batch = 1) { _, _, run ->
            seen += run.toList() to requestHere()
            stay()
        }
        flock<Nothing, Unit> {
            val actor = spawn("runs", runs)
            (1..3).forEach { n -> logAnnotated("request_id" to "r-$n") { actor.tell(n) } }
            awaitIdle()
        }
        seen shouldBe listOf(listOf(1) to "r-1", listOf(2) to "r-2", listOf(3) to "r-3")
    }

    @Test
    fun `a timer's message reaches the same step as a run of one`() {
        val seen = CopyOnWriteArrayList<List<Int>>()
        val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, run ->
            seen += run.toList()
            stop()
        }.onStart { ctx -> ctx.timers.after("soon", Duration.ZERO, 7) }
        flock<Nothing, Unit> {
            watch(spawn("runs", runs)).await()
        }
        seen shouldBe listOf(listOf(7))
    }

    @Test
    fun `a run that stops is heard as a stop by its signal handler`() {
        val heard = CopyOnWriteArrayList<Signal>()
        val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, _ -> stop() }
            .onSignal { _, _, signal -> stay().also { heard += signal } }
        flock<Nothing, Unit> {
            val actor = spawn("runs", runs)
            actor.tell(1)
            watch(actor).await()
        }
        heard shouldBe listOf<Signal>(Signal.Stopping)
    }

    @Test
    fun `a run answered unhandled sends every message of it to dead letters`() {
        val letters = CopyOnWriteArrayList<DeadLetter>()
        val opened = CountDownLatch(1)
        val runs = batching<Int, Unit>(Unit, batch = 64) { _, _, _ -> unhandled() }.onStart { opened.await() }
        flock<Nothing, Unit> {
            onDeadLetter(letters::add)
            val actor = spawn("runs", runs)
            (1..3).forEach(actor::tell)
            opened.countDown()
            awaitIdle()
        }
        letters.map { it.message to it.why } shouldBe (1..3).map { it to DeadLetter.Why.Unhandled }
    }

    @Test
    fun `a raise from a run is the actor's declared failure`() {
        val runs = batching<Int, Unit, String>(Unit, batch = 64) { _, _, run -> fail("refused ${run.toList()}") }.test()

        runs.send(1)

        runs.failure shouldBe Failure.Declared("refused [1]")
    }
}
