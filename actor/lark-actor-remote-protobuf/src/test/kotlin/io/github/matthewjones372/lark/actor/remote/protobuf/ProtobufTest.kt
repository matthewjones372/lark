package io.github.matthewjones372.lark.actor.remote.protobuf

import arrow.core.right
import com.google.protobuf.BoolValue
import com.google.protobuf.Int64Value
import com.google.protobuf.Message
import com.google.protobuf.StringValue
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
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

private fun text(value: String): StringValue = StringValue.of(value)

/** A request for a price, and the lark reply its answer goes to: what `asked` writes. */
private data class Quote(val request: StringValue, val reply: Reply<Long>)

private val quote = Protobuf.asked(StringValue.parser(), Codecs.long, ::Quote, Quote::request, Quote::reply)

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

private fun <M : Any> MessageCodec<M>.roundTrip(message: M, refs: Refs = Kept()): M =
    decode(encode(message, refs), refs)

class ProtobufTest {

    private val protocol = Protobuf.oneOf {
        message(1, StringValue.parser())
        message(2, Int64Value.parser())
    }

    @Test
    fun `a generated message crosses as its own bytes and reads back equal`() {
        Protobuf.codec(StringValue.parser()).roundTrip(text("order-42")) shouldBe text("order-42")
    }

    @Test
    fun `each message of a protocol reads back as the type it was written as`() {
        listOf<Message>(text("placed"), Int64Value.of(7)).map { protocol.roundTrip(it) } shouldBe
            listOf(text("placed"), Int64Value.of(7))
    }

    @Test
    fun `a protocol with a tag or a type given twice fails when it is built`() {
        shouldThrow<IllegalArgumentException> {
            Protobuf.oneOf {
                message(1, StringValue.parser())
                message(1, Int64Value.parser())
            }
        }.message shouldContain "tag 1"
        shouldThrow<IllegalArgumentException> {
            Protobuf.oneOf {
                message(1, StringValue.parser())
                message(2, StringValue.parser())
            }
        }.message shouldContain "StringValue"
    }

    @Test
    fun `a message the protocol has no tag for is refused, and a tag it does not know is not read`() {
        shouldThrow<IllegalArgumentException> { protocol.roundTrip(BoolValue.of(true)) }
        val unknown = Protobuf.oneOf { message(9, StringValue.parser()) }.encode(text("x"), Kept())

        shouldThrow<WireException> { protocol.decode(unknown, Kept()) }.message shouldContain "tag 9"
    }

    @Test
    fun `bytes that are not the message are a wire exception, not Protobuf's own`() {
        val notAString = Codecs.bytes.encode(byteArrayOf(0x0a, 0x7f), Kept())

        shouldThrow<WireException> { Protobuf.codec(StringValue.parser()).decode(notAString, Kept()) }
    }

    @Test
    fun `an ask whose request is a generated message is answered from another node`() {
        val port = ServerSocket(0).use(ServerSocket::getLocalPort)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val prices = Thread.ofPlatform().start {
            flock<Nothing, Unit> {
                // Prices an item at a hundred a letter.
                val pricing = behaviour<Quote, Unit>(Unit) { _, _, asked ->
                    stay().also { asked.reply(asked.request.value.length * 100L) }
                }
                val desk = spawn("prices", pricing)
                node("prices", port).expose(desk, quote)
                ready.countDown()
                done.await()
            }
        }
        ready.await()
        try {
            val answer = flock<Nothing, Any> {
                val desk = node("shop", ServerSocket(0).use(ServerSocket::getLocalPort))
                    .remote(Address("prices@127.0.0.1:$port", "/user/prices", 0), quote)
                desk.ask(1.minutes) { Quote(text("apples"), it) }
            }

            answer shouldBe 600L.right().right()
        } finally {
            done.countDown()
            prices.join()
        }
    }
}
