package io.github.matthewjones372.lark.actor

import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private sealed interface Drawer

/** Puts [pence] in, and then the [then] more it was told to, as a second message to itself, after [lodged]. */
private data class Lodge(val pence: Int, val then: Int = 0) : Drawer

private data class Balance(val reply: Reply<Int>) : Drawer

private val coins = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private fun drawer(lodged: () -> Unit = {}) = persistent<Drawer, Int, Int>(
    id = PersistenceId("till", "1"),
    empty = 0,
    codec = coins,
    command = { ctx, _, command ->
        when (command) {
            is Lodge -> persist(command.pence).then {
                if (command.then > 0) {
                    ctx.self.tell(Lodge(command.then))
                    lodged()
                }
            }

            is Balance -> none().then { command.reply(it) }
        }
    },
    event = { total, pence -> total + pence },
)

private fun courier(to: ActorRef<Drawer>): Behaviour<Int, Unit, Nothing> = behaviour(Unit) { _, _, pence ->
    to.tell(Lodge(pence))
    stay()
}

class CrashTest {
    @Test
    fun `an actor crashed between two persists recovers the first and nothing more`() {
        testActors {
            val crashing = AtomicReference<ActorRef<Drawer>>()
            val till = spawn("till", drawer { crash(crashing.get()) }).also(crashing::set)
            till.tell(Lodge(10, then = 5))
            withClue(trace) { till.ask { Balance(it) } shouldBe 10.right() }
            trace shouldContain TraceEntry.Crashed(till.address)
            trace.filterIsInstance<TraceEntry.Delivered>().map { it.message } shouldNotContain Lodge(5)
        }
    }

    @Test
    fun `a crash from the test loses the actor's timers, and its next message recovers it`() {
        testActors {
            val till = spawn("till", drawer())
            val ticker = spawn(
                "ticker",
                behaviour<String, Int>(0) { ctx, ticks, _ ->
                    ctx.timers.after("tick", 1.seconds, "tick")
                    become(ticks + 1)
                },
            )
            till.tell(Lodge(3))
            ticker.tell("start")
            crash(till)
            crash(ticker)
            pendingTimers shouldBe 0
            ticker.state shouldBe 0
            till.ask { Balance(it) } shouldBe 3.right()
            trace.filterIsInstance<TraceEntry.Crashed>().map { it.actor.path } shouldContainExactly
                listOf("/user/till", "/user/ticker")
        }
    }

    @Test
    fun `a crash on the way loses the message that caused it`() {
        testActors(order = Ordering.seeded(1), faults = Faults(crash = 1.0)) {
            val till = spawn("till", drawer())
            spawn("courier", courier(till)).tell(7)
            till.ask { Balance(it) } shouldBe 0.right()
        }
    }

    @Test
    fun `a stopped actor cannot crash`() {
        testActors {
            val done = spawn("done", behaviour<String, Unit>(Unit) { _, _, _ -> stop() })
            done.tell("stop")
            shouldThrow<IllegalStateException> { crash(done) }
        }
    }
}
