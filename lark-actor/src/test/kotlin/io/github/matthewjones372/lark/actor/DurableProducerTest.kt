package io.github.matthewjones372.lark.actor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/** Tops a wallet up by [pence], sent durably. */
private data class TopUp(val pence: Long, override val delivery: Delivery) : Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

/** A top-up as a durable producer keeps it: the pence and whose it is, with nowhere to confirm to. */
private val topUpCodec = object : EventCodec<TopUp> {
    override fun encode(event: TopUp): ByteArray =
        with(event.delivery) { "${event.pence}|$producer|$to|$sequence" }.toByteArray()

    override fun decode(bytes: ByteArray): TopUp {
        val (pence, producer, to, sequence) = String(bytes).split("|")
        return TopUp(pence.toLong(), Delivery(producer, to, sequence.toLong(), Delivery.NoOne))
    }
}

private val pence = object : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(event).array()

    override fun decode(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).long
}

private fun walletOf(id: String) = PersistenceId("wallet", id)

private fun wallet(id: String) = delivered(
    persistent<TopUp, Long, Long>(
        id = walletOf(id),
        empty = 0,
        codec = pence,
        command = { _, _, topUp -> persist(topUp.pence) },
        event = { balance, paid -> balance + paid },
    ),
)

/** Never answers: a node that has gone. */
private val nowhere = object : ActorRef<TopUp> {
    override val address = Address("test", "/nowhere", 0)

    override fun tell(message: TopUp) = Unit
}

class DurableProducerTest {

    private val ids = (0 until 5).map { "w-$it" }

    @Test
    fun `a producer started again under its id sends every unconfirmed command once, and numbers on after them`() {
        testActors {
            val wallets = ids.associateWith { spawn(it, wallet(it)) }
            val crashed = durableProducer("till", topUpCodec) { nowhere }
            for (n in 0 until 50) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }

            val again = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
            again.send("w-0") { TopUp(1_000, it) }

            ids.forEachIndexed { w, id ->
                val sent = (0 until 50).filter { it % 5 == w }.map(Int::toLong)
                journal.events(walletOf(id), pence) shouldContainExactly if (w == 0) sent + 1_000 else sent
            }
            wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 11L)
        }
    }

    @Test
    fun `snapshots keep the outbox small, and a producer started from one numbers on`() {
        testActors {
            val wallets = ids.associateWith { spawn(it, wallet(it)) }
            val producer = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
            for (n in 0 until 1_500) producer.send(ids[n % 5]) { TopUp(1, it) }

            // 3,000 events written; pruning keeps no more than the 1,000 since the snapshot before the newest.
            journal.read(PersistenceId("lark-producer", "till")).size shouldBeLessThanOrEqual 1_000
            durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }.send("w-0") { TopUp(7, it) }

            journal.events(walletOf("w-0"), pence).last() shouldBe 7L
            wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 301L)
        }
    }
}
