package io.github.matthewjones372.lark.actor

import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

private sealed interface Gate

/** Parks the step until the test opens the gate: a blocking step, as a read or a lock would be. */
private data class Stuck(val open: CountDownLatch) : Gate

private data class Knock(val reply: Reply<String>) : Gate

private data object Crash : Gate

private fun gate() = behaviour<Gate, Unit>(Unit) { _, _, message ->
    when (message) {
        is Stuck -> message.open.await()
        is Knock -> message.reply("open")
        Crash -> error("the gate fell off its hinges")
    }
    stay()
}

private fun counter(counted: AtomicInteger) = behaviour<Unit, Unit>(Unit) { _, _, _ ->
    counted.incrementAndGet()
    stay()
}

class RunnersTest {

    @Test
    fun `on threads, actors parked in their steps do not hold up the rest`() {
        // More blocked actors than there are carriers, so every runner the flock starts with is parked.
        val blocked = Runtime.getRuntime().availableProcessors() * 4 + 4
        val open = CountDownLatch(1)
        val answer = flock<Nothing, Any> {
            val stuck = (1..blocked).map { spawn("stuck-$it", gate()) }
            stuck.forEach { it.tell(Stuck(open)) }
            val free = spawn("free", gate())
            val answer = free.ask(1.minutes) { Knock(it) }
            open.countDown()
            awaitIdle()
            answer
        }

        answer shouldBe "open".right().right()
    }

    @Test
    fun `on threads, an actor's throw does not take its runner, or any other actor, with it`() {
        val answer = flock<Nothing, Any> {
            val falling = spawn("falling", gate())
            falling.tell(Crash)
            watch(falling).await()
            spawn("standing", gate()).ask(1.minutes) { Knock(it) }
        }

        answer shouldBe "open".right().right()
    }

    @Test
    fun `on threads, awaitIdle waits for a burst the runners share`() {
        val counted = AtomicInteger()
        val seen = flock<Nothing, Int> {
            val counters = (1..2_000).map { spawn("counter-$it", counter(counted)) }
            counters.forEach { it.tell(Unit) }
            awaitIdle()
            counted.get()
        }

        seen shouldBe 2_000.right()
    }
}
