package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.flock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration

/** Spec 0085: a behaviour with `steps` is handed the plain messages already waiting, a run at a time. */
class StepsTest {

    /** Every run it is handed, and every message handed it alone, in the order they came. */
    private class Runs(
        val batch: Int,
        val opened: CountDownLatch = CountDownLatch(1),
        val armed: CountDownLatch? = null,
        val told: CountDownLatch = CountDownLatch(0),
    ) {
        val seen = CopyOnWriteArrayList<List<Int>>()

        fun behaviour(): Behaviour<Int, Unit, Nothing> = Behaviour(
            initial = Unit,
            step = { _, _, n ->
                seen += listOf(n)
                // The timer's message holds the actor until the test has told what comes after it.
                if (n == 0) told.await()
                stay()
            },
            signal = null,
            start = { ctx, _ ->
                opened.await()
                if (armed != null) {
                    ctx.timers.after("between", Duration.ZERO, 0)
                    armed.countDown()
                }
                stay()
            },
            steps = { _, _, run ->
                seen += run
                Batched(stay(), emptyList(), emptyList())
            },
            batch = batch,
        )
    }

    @Test
    fun `the messages already waiting are handed over as one run, in order`() {
        val runs = Runs(batch = 64)
        flock<Nothing, Unit> {
            val actor = spawn("runs", runs.behaviour())
            (1..3).forEach(actor::tell)
            runs.opened.countDown()
            awaitIdle()
        }
        runs.seen shouldBe listOf(listOf(1, 2, 3))
    }

    @Test
    fun `a run is no longer than the batch`() {
        val runs = Runs(batch = 2)
        flock<Nothing, Unit> {
            val actor = spawn("runs", runs.behaviour())
            (1..5).forEach(actor::tell)
            runs.opened.countDown()
            awaitIdle()
        }
        runs.seen shouldBe listOf(listOf(1, 2), listOf(3, 4), listOf(5))
    }

    @Test
    fun `a timer between messages ends the run, and is handled alone`() {
        val runs = Runs(batch = 64, armed = CountDownLatch(1), told = CountDownLatch(1))
        flock<Nothing, Unit> {
            val actor = spawn("runs", runs.behaviour())
            actor.tell(1)
            actor.tell(2)
            runs.opened.countDown()
            runs.armed?.await()
            actor.tell(3)
            actor.tell(4)
            runs.told.countDown()
            awaitIdle()
        }
        runs.seen shouldBe listOf(listOf(1, 2), listOf(0), listOf(3, 4))
    }

    @Test
    fun `a behaviour without steps still takes one message at a time`() {
        val seen = CopyOnWriteArrayList<Int>()
        val opened = CountDownLatch(1)
        val one = Behaviour<Int, Unit, Nothing>(
            initial = Unit,
            step = { _, _, n ->
                seen += n
                stay()
            },
            start = { _, _ ->
                opened.await()
                stay()
            },
        )
        flock<Nothing, Unit> {
            val actor = spawn("one", one)
            (1..3).forEach(actor::tell)
            opened.countDown()
            awaitIdle()
        }
        seen shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `what a run leaves unhandled or unrun becomes a dead letter`() {
        val letters = CopyOnWriteArrayList<DeadLetter>()
        val opened = CountDownLatch(1)
        val stopping = Behaviour<Int, Unit, Nothing>(
            initial = Unit,
            step = { _, _, _ -> stay() },
            signal = null,
            start = { _, _ ->
                opened.await()
                stay()
            },
            steps = { _, _, run -> Batched(stop(), unhandled = listOf(run[0]), unrun = run.drop(2)) },
            batch = 64,
        )
        flock<Nothing, Unit> {
            onDeadLetter(letters::add)
            val actor = spawn("stopping", stopping)
            (1..4).forEach(actor::tell)
            opened.countDown()
            watch(actor).await()
        }
        letters.map { it.message to it.why } shouldBe listOf(
            1 to DeadLetter.Why.Unhandled,
            3 to DeadLetter.Why.Stopped,
            4 to DeadLetter.Why.Stopped,
        )
    }
}
