package io.github.matthewjones372.lark.actor.remote.avro

import arrow.core.right
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Refs
import io.github.matthewjones372.lark.actor.remote.WireException
import io.github.matthewjones372.lark.actor.remote.decode
import io.github.matthewjones372.lark.actor.remote.encode
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.avro.message.BinaryMessageEncoder
import org.apache.avro.message.SchemaStore
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

/** A placed order to price, and the lark reply its price goes to: what `asked` writes. */
private data class Price(val placed: Placed, val reply: Reply<Long>)

/** Refs that keep every reply they are given, and hand the same one back for its address. */
private class Kept : Refs {
    private val replies = mutableMapOf<Address, Reply<*>>()

    override fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>) = ref.address

    override fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>): Address =
        Address("here", "/temp/reply-${replies.size}", 0).also { replies[it] = reply }

    override fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M> = error("no refs here")

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any> reply(address: Address, answers: MessageCodec<A>) = replies.getValue(address) as Reply<A>
}

class AvroTest {

    private val schemas = SchemaStore.Cache().apply { addSchema(Placed.VERSION_2) }
    private val placed = Avro.codec(Placed::class.java, schemas)
    private val price = Avro.asked(Placed::class.java, schemas, Codecs.long, ::Price, Price::placed, Price::reply)

    @Test
    fun `a record crosses in single-object encoding and reads back equal`() {
        placed.decode(placed.encode(Placed("o-42", 250), Kept()), Kept()) shouldBe Placed("o-42", 250)
    }

    @Test
    fun `a record a node wrote with a newer schema is read by one on the older, through the store`() {
        val newer = GenericData.Record(Placed.VERSION_2).apply {
            put("order", "o-7")
            put("pence", 120L)
            put("currency", "EUR")
        }
        val written = BinaryMessageEncoder<GenericRecord>(GenericData.get(), Placed.VERSION_2).encode(newer)
        val frame = Codecs.bytes.encode(ByteArray(written.remaining()).also(written::get), Kept())

        placed.decode(frame, Kept()) shouldBe Placed("o-7", 120)
    }

    @Test
    fun `a record whose schema no store has, or bytes that are not a record, is a wire exception`() {
        val unknown = Avro.codec(Placed::class.java, SchemaStore.Cache())
        val newer = GenericData.Record(Placed.VERSION_2).apply {
            put("order", "o-7")
            put("pence", 120L)
            put("currency", "EUR")
        }
        val written = BinaryMessageEncoder<GenericRecord>(GenericData.get(), Placed.VERSION_2).encode(newer)

        shouldThrow<WireException> {
            unknown.decode(Codecs.bytes.encode(ByteArray(written.remaining()).also(written::get), Kept()), Kept())
        }
        shouldThrow<WireException> { placed.decode(Codecs.bytes.encode(byteArrayOf(1, 2, 3), Kept()), Kept()) }
    }

    @Test
    fun `an ask whose request is a record is answered from another node`() {
        val port = ServerSocket(0).use(ServerSocket::getLocalPort)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val pricing = Thread.ofPlatform().start {
            flock<Nothing, Unit> {
                // Adds a fifth for the tax.
                val taxed = behaviour<Price, Unit>(Unit) { _, _, asked ->
                    stay().also { asked.reply(asked.placed.pence * 6 / 5) }
                }
                node("pricing", port).expose(spawn("pricing", taxed), price)
                ready.countDown()
                done.await()
            }
        }
        ready.await()
        try {
            val answer = flock<Nothing, Any> {
                val desk = node("shop", ServerSocket(0).use(ServerSocket::getLocalPort))
                    .remote(Address("pricing@127.0.0.1:$port", "/user/pricing", 0), price)
                desk.ask(1.minutes) { Price(Placed("o-1", 500), it) }
            }

            answer shouldBe 600L.right().right()
        } finally {
            done.countDown()
            pricing.join()
        }
    }
}
