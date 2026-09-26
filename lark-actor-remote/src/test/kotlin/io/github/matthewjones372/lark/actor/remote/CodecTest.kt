package io.github.matthewjones372.lark.actor.remote

import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.protocolFaults
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private sealed interface Clinic

private data class Book(val pet: String, val grams: Int, val note: String?) : Clinic

private data class Weigh(val reply: Reply<Int>) : Clinic

private data class Refer(val to: ActorRef<Clinic>, val since: Long) : Clinic

private data object Close : Clinic

/** The clinic's messages, field by field, with a tag in front of each. */
private val clinicCodec = object : MessageCodec<Clinic> {
    override fun write(message: Clinic, out: WireOut) = when (message) {
        is Book -> {
            out.int(1)
            out.string(message.pet)
            out.int(message.grams)
            out.nullable(message.note, out::string)
        }

        is Weigh -> {
            out.int(2)
            out.reply(message.reply, Codecs.int)
        }

        is Refer -> {
            out.int(3)
            out.ref(message.to, this)
            out.long(message.since)
        }

        Close -> out.int(4)
    }

    override fun read(input: WireIn): Clinic = when (val tag = input.int()) {
        1 -> Book(input.string(), input.int(), input.nullable(input::string))
        2 -> Weigh(input.reply(Codecs.int))
        3 -> Refer(input.ref(this), input.long())
        4 -> Close
        else -> error("no clinic message has the tag $tag")
    }
}

/** Refs as a transport would make them: an address on the way out, and a ref for that address on the way in. */
private class AddressedRefs : Refs {
    val told = mutableListOf<Pair<Address, Any>>()

    override fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>): Address = ref.address

    override fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>): Address = reply.address

    override fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M> = object : ActorRef<M> {
        override val address = address

        override fun tell(message: M) {
            told += address to message
        }
    }

    override fun <A : Any> reply(address: Address, answers: MessageCodec<A>): Reply<A> = object : Reply<A> {
        override val address = address

        override fun invoke(answer: A) {
            told += address to answer
        }
    }
}

class CodecTest {

    private val refs = AddressedRefs()
    private val there = Address("shop-2@10.0.0.7:25520", "/user/clinic", 7)

    private fun Clinic.roundTrip(): Clinic = clinicCodec.decode(clinicCodec.encode(this, refs), refs)

    @Test
    fun `a message of plain fields comes back equal, a missing note included`() {
        Book("rex", 50, "limps").roundTrip() shouldBe Book("rex", 50, "limps")
        Book("bo", 7, null).roundTrip() shouldBe Book("bo", 7, null)
        Close.roundTrip() shouldBe Close
    }

    @Test
    fun `a reply crosses as its address, and answering the one that comes back answers that address`() {
        val asked = refs.reply(Address("shop-1@10.0.0.6:25520", "/temp/ask-3", 1), Codecs.int)

        val weigh = Weigh(asked).roundTrip().shouldBeInstanceOf<Weigh>()
        weigh.reply(75)

        weigh.reply.address shouldBe asked.address
        refs.told shouldContainExactly listOf(asked.address to 75)
    }

    @Test
    fun `a ref crosses as its address, incarnation included, and tells that address`() {
        val sent = Refer(refs.ref(there, clinicCodec), since = 1_700_000_000_000)
        val refer = sent.roundTrip().shouldBeInstanceOf<Refer>()
        refer.to.tell(Close)

        refer.since shouldBe 1_700_000_000_000
        refer.to.address shouldBe there
        refs.told shouldContainExactly listOf(there to Close)
    }

    @Test
    fun `text past what a modified UTF-8 string holds, and outside the basic plane, comes back whole`() {
        val long = "é".repeat(40_000) + "🐾"

        Book(long, 1, null).roundTrip() shouldBe Book(long, 1, null)
    }

    @Test
    fun `reading past the end of a message is an error that says so`() {
        val cut = clinicCodec.encode(Book("rex", 50, null), refs).copyOf(3)

        shouldThrow<WireException> { clinicCodec.decode(cut, refs) }.message shouldBe
            "a message ended after 3 bytes, part way through what its codec reads"
    }

    @Test
    fun `the answers most asks want come back as they went`() {
        Codecs.int.decode(Codecs.int.encode(-7, refs), refs) shouldBe -7
        Codecs.string.decode(Codecs.string.encode("rex", refs), refs) shouldBe "rex"
        val longs = Codecs.list(Codecs.long)
        longs.decode(longs.encode(listOf(1L, 2L), refs), refs) shouldBe listOf(1L, 2L)
        Codecs.unit.decode(Codecs.unit.encode(Unit, refs), refs) shouldBe Unit
    }

    @Test
    fun `the clinic's messages could all cross`() {
        protocolFaults<Clinic>().shouldBeEmpty()
    }
}
