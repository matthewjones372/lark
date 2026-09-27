package io.github.matthewjones372.lark.actor

import arrow.core.left
import arrow.core.right
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
            again.drain(Duration.ZERO) shouldBe true
            again.send("w-0") { TopUp(1_000, it) }

            ids.forEachIndexed { w, id ->
                val sent = (0 until 50).filter { it % 5 == w }.map(Int::toLong)
                journal.events(walletOf(id), pence) shouldContainExactly if (w == 0) sent + 1_000 else sent
            }
            wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 11L)
        }
    }

    @Test
    fun `across databases the outbox sits in its producer's own database, and a restart finds it there`() {
        val databases = listOf("db-a" to InMemoryJournal(), "db-b" to InMemoryJournal())
        val sharded = ShardedJournal(databases)
        testActors(journal = sharded) {
            val wallets = ids.associateWith { spawn(it, wallet(it)) }
            val crashed = durableProducer("till", topUpCodec) { nowhere }
            for (n in 0 until 10) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }

            val outbox = PersistenceId("lark-producer", "till")
            databases.forEach { (name, kept) ->
                kept.read(outbox).isNotEmpty() shouldBe
                    (name == sharded.database(outbox))
            }

            val again = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
            again.drain(Duration.ZERO) shouldBe true
            ids.sumOf { journal.events(walletOf(it), pence).size } shouldBe 10
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

    @Test
    fun `what a start recovers holds room, so a drain waits for it`() {
        testActors {
            val crashed = durableProducer("till", topUpCodec) { nowhere }
            for (n in 0 until 3) crashed.send("w-$n") { TopUp(n.toLong(), it) }

            val again = durableProducer("till", topUpCodec, keep = 5, within = Duration.ZERO) { nowhere }

            again.drain(Duration.ZERO) shouldBe false
            again.send("w-0") { TopUp(9, it) } shouldBe Unit.right()
            again.send("w-1") { TopUp(9, it) } shouldBe Unit.right()
            again.send("w-2") { TopUp(9, it) } shouldBe Full.left()
        }
    }

    @Test
    fun `a producer resumed under an actor sends what an earlier run kept, and then drains`() {
        testActors {
            val wallets = ids.associateWith { spawn(it, wallet(it)) }
            val crashed = durableProducer("till", topUpCodec, keep = 10) { nowhere }
            for (n in 0 until 10) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }
            val resumed = AtomicReference<Producer<TopUp>>()
            val resumer = behaviour<Unit, Unit>(Unit) { _, _, _ -> stay() }.onStart { ctx ->
                resumed.set(ctx.resumedProducer("till", topUpCodec, 2.seconds) { id -> wallets.getValue(id) }.first)
            }

            spawn("resumer", resumer)

            resumed.get().drain(Duration.ZERO) shouldBe true
            ids.sumOf { journal.events(walletOf(it), pence).size } shouldBe 10
        }
    }
}
