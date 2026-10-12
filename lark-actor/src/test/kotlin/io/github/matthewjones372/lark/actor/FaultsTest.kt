package io.github.matthewjones372.lark.actor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun pong(): Behaviour<ActorRef<String>, Int, Nothing> = behaviour(0) { _, answered, asker ->
    asker.tell("pong")
    become(answered + 1)
}

/** Asks [server] until it hears back, asking again each second. */
private fun asker(server: ActorRef<ActorRef<String>>): Behaviour<String, Boolean, Nothing> =
    behaviour(false) { ctx, answered, message ->
        when {
            message == "pong" -> {
                ctx.timers.cancel("again")
                become(true)
            }

            answered -> stay()

            else -> {
                server.tell(ctx.self)
                ctx.timers.after("again", 1.seconds, "again")
                stay()
            }
        }
    }

private fun echo(to: ActorRef<String>): Behaviour<String, Unit, Nothing> = behaviour(Unit) { _, _, word ->
    to.tell(word)
    stay()
}

private fun heard(): Behaviour<String, List<String>, Nothing> =
    behaviour(emptyList()) { _, seen, word -> become(seen + word) }

class FaultsTest {
    @Test
    fun `a requester that asks again completes when half of all messages are lost`() {
        (1L..20L).forEach { seed ->
            testActors(order = Ordering.seeded(seed), faults = Faults(drop = 0.5)) {
                val asker = spawn("asker", asker(spawn("server", pong())))
                asker.tell("ask")
                settle()
                withClue("seed $seed: $trace") { asker.state shouldBe true }
            }
        }
    }

    @Test
    fun `the trace shows each message lost`() {
        val dropped = (1L..20L).flatMap { seed ->
            testActors(order = Ordering.seeded(seed), faults = Faults(drop = 0.5)) {
                spawn("asker", asker(spawn("server", pong()))).tell("ask")
                settle()
                trace.filterIsInstance<TraceEntry.Dropped>()
            }
        }
        dropped.shouldNotBeEmpty()
        dropped.map { it.message }.toSet().forEach { lost ->
            withClue(lost) { (lost == "pong" || lost is ActorRef<*>) shouldBe true }
        }
    }

    @Test
    fun `a duplicated message arrives twice`() {
        testActors(order = Ordering.seeded(1), faults = Faults(duplicate = 1.0)) {
            val log = spawn("log", heard())
            spawn("echo", echo(log)).tell("hello")
            log.state shouldContainExactly listOf("hello", "hello")
            trace.filterIsInstance<TraceEntry.Duplicated>() shouldHaveSize 1
        }
    }

    @Test
    fun `a delayed message waits for the test's time to reach it`() {
        testActors(order = Ordering.seeded(1), faults = Faults(delay = 1.0, maxDelay = 2.seconds)) {
            val log = spawn("log", heard())
            spawn("echo", echo(log)).tell("hello")
            log.state.shouldBeEmpty()
            val delayed = trace.filterIsInstance<TraceEntry.Delayed>().single()
            withClue(delayed) { (delayed.by > Duration.ZERO && delayed.by <= 2.seconds) shouldBe true }
            settle()
            log.state shouldContainExactly listOf("hello")
        }
    }

    @Test
    fun `a tell from the test itself is never lost`() {
        testActors(order = Ordering.seeded(1), faults = Faults(drop = 1.0)) {
            val log = spawn("log", heard())
            log.tell("hello")
            log.state shouldContainExactly listOf("hello")
        }
    }

    @Test
    fun `faults draw from the order's seed, so they need one`() {
        shouldThrow<IllegalArgumentException> { testActors(faults = Faults(drop = 0.1)) { } }
    }
}
