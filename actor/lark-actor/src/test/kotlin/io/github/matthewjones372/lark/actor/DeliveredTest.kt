package io.github.matthewjones372.lark.actor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

private sealed interface TillCommand

/** Adds [pence], sent reliably. */
private data class TillSale(val pence: Int, override val delivery: Delivery) :
    TillCommand,
    Delivered

/** Adds [pence], sent plainly. */
private data class TillCash(val pence: Int) : TillCommand

/** Fails the step, sent reliably. */
private data class TillStuck(override val delivery: Delivery) :
    TillCommand,
    Delivered

private fun register() = delivered(
    behaviour<TillCommand, Int, String>(0) { _, total, command ->
        when (command) {
            is TillSale -> become(total + command.pence)
            is TillCash -> become(total + command.pence)
            is TillStuck -> raise("stuck")
        }
    },
)

/** A till that is shut until the first cash opens it, keeping each sale until then. */
private fun shutTill() = delivered(
    behaviour<TillCommand, Int?, String>(null) { ctx, total, command ->
        when {
            total != null && command is TillSale -> become(total + command.pence)
            command is TillSale -> stay().also { ctx.stash(command) }
            command is TillCash -> become((total ?: 0) + command.pence).also { ctx.unstashAll() }
            else -> raise("stuck")
        }
    },
)

class DeliveredTest {

    private val confirmed = ConcurrentLinkedQueue<Confirmed>()

    @Test
    fun `a delivered command is confirmed once its step has run, and a plain one is not`() {
        testActors {
            val confirms =
                spawn("confirms", behaviour<Confirmed, Unit>(Unit) { _, _, c -> stay().also { confirmed += c } })
            val till = spawn("till", register())
            val from = { sequence: Long -> Delivery("checkout", "till", sequence, confirms) }

            till.send(TillSale(10, from(1)))
            till.send(TillCash(5))
            till.send(TillSale(20, from(2)))

            till.state shouldBe 35
            confirmed.toList() shouldContainExactly listOf(Confirmed("till", 1), Confirmed("till", 2))
        }
    }

    @Test
    fun `a step that fails confirms nothing, so the command is sent again`() {
        testActors {
            val confirms =
                spawn("confirms", behaviour<Confirmed, Unit>(Unit) { _, _, c -> stay().also { confirmed += c } })
            val till = spawn("till", register())

            till.send(TillStuck(Delivery("checkout", "till", 1, confirms)))

            till.failure shouldBe Failure.Raised("stuck")
            confirmed.toList() shouldContainExactly emptyList()
        }
    }

    @Test
    fun `a command stashed is confirmed once it is handled, not when it is kept`() {
        testActors {
            val confirms =
                spawn("confirms", behaviour<Confirmed, Unit>(Unit) { _, _, c -> stay().also { confirmed += c } })
            val till = spawn("till", shutTill())

            till.send(TillSale(10, Delivery("checkout", "till", 1, confirms)))
            confirmed.toList() shouldBe emptyList()

            till.send(TillCash(5))
            till.state shouldBe 15
            confirmed.toList() shouldContainExactly listOf(Confirmed("till", 1))
        }
    }
}
