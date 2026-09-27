package io.github.matthewjones372.lark.actor

import arrow.core.left
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.time.Duration

/** Tops a wallet up by [pence], sent durably. */
private data class TopUp(val pence: Long, override val delivery: Delivery) : Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

/** Tops a wallet up, and cannot be sent again with a new delivery. */
private data class OneOff(override val delivery: Delivery) : Delivered

/** A top-up as a durable producer keeps it: the pence and whose it is, with nowhere to confirm to. */
private val topUpCodec = object : EventCodec<TopUp> {
    override fun encode(event: TopUp): ByteArray {
        require(event.pence >= 0) { "a top-up of ${event.pence} is refused" }
        return with(event.delivery) { "${event.pence}|$producer|$to|$sequence" }.toByteArray()
    }

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
    fun `a command the codec refuses, or that cannot be sent again, throws to its sender and holds no room`() {
        testActors {
            val wallets = ids.associateWith { spawn(it, wallet(it)) }
            val producer =
                durableProducer("till", topUpCodec, keep = 1, within = Duration.ZERO) { wallets.getValue(it) }
            val oneOffs = object : EventCodec<OneOff> {
                override fun encode(event: OneOff) = event.delivery.to.toByteArray()

                override fun decode(bytes: ByteArray) = OneOff(Delivery("till", String(bytes), 0, Delivery.NoOne))
            }

            shouldThrow<IllegalArgumentException> { producer.send("w-0") { TopUp(-1, it) } }
            shouldThrow<UnsupportedOperationException> {
                durableProducer("once", oneOffs) { void<OneOff>() }.send("w-0", ::OneOff)
            }
            producer.send("w-0") { TopUp(3, it) } shouldBe Unit.right()

            journal.events(walletOf("w-0"), pence) shouldContainExactly listOf(3L)
            journal.read(PersistenceId("lark-producer", "once")) shouldBe emptyList()
            producer.drain(Duration.ZERO) shouldBe true
        }
    }

    @Test
    fun `a start that recovers more than it may keep drains only once every one is confirmed`() {
        testActors {
            val crashed = durableProducer("till", topUpCodec) { nowhere }
            for (n in 1..5) crashed.send("w-0") { TopUp(n.toLong(), it) }
            val arrived = ArrayDeque<TopUp>()
            val wallet =
                spawn("wallet", behaviour<TopUp, Unit>(Unit) { _, _, topUp -> stay().also { arrived += topUp } })

            val again = durableProducer("till", topUpCodec, keep = 2) { wallet }
            repeat(2) { arrived.removeFirst().delivery.confirm() }
            again.drain(Duration.ZERO) shouldBe false

            repeat(3) { arrived.removeFirst().delivery.confirm() }
            again.drain(Duration.ZERO) shouldBe true
        }
    }
}

/** Tells nobody: an entity whose node has gone. */
private fun <M : Any> void(): ActorRef<M> = object : ActorRef<M> {
    override val address = Address("test", "/void", 0)

    override fun tell(message: M) = Unit
}
