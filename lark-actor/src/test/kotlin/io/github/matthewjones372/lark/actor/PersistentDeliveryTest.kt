package io.github.matthewjones372.lark.actor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

private data class Paid(val pence: Int)

private val paidCodec = object : EventCodec<Paid> {
    override fun encode(event: Paid): ByteArray = "${event.pence}".toByteArray()

    override fun decode(bytes: ByteArray): Paid = Paid(String(bytes).toInt())
}

private val purseCodec = object : StateCodec<Int> {
    override fun encode(state: Int): ByteArray = "$state".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private sealed interface PurseCommand

/** Pays [pence] in, sent reliably. */
private data class PayIn(val pence: Int, override val delivery: Delivery) :
    PurseCommand,
    Delivered

/** Persists nothing and says so, sent reliably. */
private data class Peek(override val delivery: Delivery) :
    PurseCommand,
    Delivered

private val purseId = PersistenceId("purse", "p-1")

/** A purse paid into reliably, snapshotting every [every] entries when asked, and pruning what snapshots cover. */
private fun purse(peeks: ConcurrentLinkedQueue<Long>, every: Int? = null) = delivered(
    persistent<PurseCommand, Paid, Int>(
        id = purseId,
        empty = 0,
        codec = paidCodec,
        command = { _, _, command ->
            when (command) {
                is PayIn -> persist(Paid(command.pence))
                is Peek -> none().then { peeks += command.delivery.sequence }
            }
        },
        event = { pence, paid -> pence + paid.pence },
        snapshots = every?.let { every(it, purseCodec, prune = Prune.always) },
    ),
)

class PersistentDeliveryTest {

    private val confirmed = ConcurrentLinkedQueue<Confirmed>()
    private val peeks = ConcurrentLinkedQueue<Long>()

    private fun TestActors.confirms() =
        spawn("confirms", behaviour<Confirmed, Unit>(Unit) { _, _, c -> stay().also { confirmed += c } })

    @Test
    fun `a command sent twice is applied once and confirmed twice`() {
        testActors {
            val confirms = confirms()
            val purse = spawn("purse", purse(peeks))
            val first = Delivery("checkout", "p-1", 1, confirms)

            purse.send(PayIn(10, first))
            purse.send(PayIn(10, first))
            purse.send(PayIn(5, first.copy(sequence = 2)))

            purse.state.value shouldBe 15
            purse.state.delivered shouldBe mapOf("checkout" to 2L)
            journal.events(purseId, paidCodec) shouldContainExactly listOf(Paid(10), Paid(5))
            confirmed.map { it.sequence } shouldContainExactly listOf(1L, 1L, 2L)
        }
    }

    @Test
    fun `each producer is counted on its own`() {
        testActors {
            val confirms = confirms()
            val purse = spawn("purse", purse(peeks))

            purse.send(PayIn(10, Delivery("checkout", "p-1", 1, confirms)))
            purse.send(PayIn(20, Delivery("refunds", "p-1", 1, confirms)))

            purse.state.value shouldBe 30
        }
    }

    @Test
    fun `after a restart, and on the next owner after a move, the duplicate is still dropped`() {
        testActors {
            val confirms = confirms()
            val purse = spawn("purse", purse(peeks))
            val first = Delivery("checkout", "p-1", 1, confirms)
            purse.send(PayIn(10, first))

            purse.restart()
            purse.send(PayIn(10, first))
            val moved = spawn("moved", purse(peeks))
            moved.send(PayIn(10, first))

            purse.state.value shouldBe 10
            moved.state shouldBe Remembered(10, sequence = 2, delivered = mapOf("checkout" to 1L))
            confirmed.size shouldBe 3
        }
    }

    @Test
    fun `a snapshot remembers the deliveries whose marks were pruned`() {
        testActors {
            val confirms = confirms()
            val purse = spawn("purse", purse(peeks, every = 2))
            for (sequence in 1L..4L) purse.send(PayIn(10, Delivery("checkout", "p-1", sequence, confirms)))

            val moved = spawn("moved", purse(peeks, every = 2))
            moved.send(PayIn(10, Delivery("checkout", "p-1", 1, confirms)))

            journal.read(purseId).first().sequence shouldBe 7
            moved.state shouldBe Remembered(40, sequence = 8, delivered = mapOf("checkout" to 4L))
        }
    }

    @Test
    fun `a command that persists nothing is dropped as a duplicate until its entity stops`() {
        testActors {
            val confirms = confirms()
            val purse = spawn("purse", purse(peeks))
            val peek = Peek(Delivery("checkout", "p-1", 1, confirms))

            purse.send(peek)
            purse.send(peek)
            purse.restart()
            purse.send(peek)

            peeks.toList() shouldContainExactly listOf(1L, 1L)
            journal.read(purseId) shouldBe emptyList()
        }
    }

    @Test
    fun `a snapshot of an entity nothing was delivered to is the state's own bytes`() {
        val remembered = Remembered(42, sequence = 3)

        encode(purseCodec, remembered).decodeToString() shouldBe "42"
        decode(purseCodec, encode(purseCodec, remembered), 3) shouldBe remembered
        val delivered = remembered.copy(delivered = mapOf("checkout" to 7L, "refunds" to 2L))
        decode(purseCodec, encode(purseCodec, delivered), 3) shouldBe delivered
    }
}
