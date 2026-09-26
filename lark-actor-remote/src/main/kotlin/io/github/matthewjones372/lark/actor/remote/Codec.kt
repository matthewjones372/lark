package io.github.matthewjones372.lark.actor.remote

import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Reply
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * How a message of [M] becomes bytes to cross to another node, and back. No serialisation library is chosen here, as
 * none is for the journal: a message's wire form is written field by field, and a ref inside it goes through
 * [WireOut.ref] or [WireOut.reply], so that it crosses as an address.
 */
interface MessageCodec<M : Any> {
    fun write(message: M, out: WireOut)

    fun read(input: WireIn): M
}

/**
 * Where the refs inside a message go on the way out and come from on the way in: the transport, which knows which
 * node is which, or a test standing in for it. Each carries the codec of what will be sent to it, since the side that
 * sends it on is the side that has to write that.
 */
interface Refs {
    fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>): Address

    fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>): Address

    fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M>

    fun <A : Any> reply(address: Address, answers: MessageCodec<A>): Reply<A>
}

/** A message that could not be read: cut short, or not what its codec expected. */
class WireException(message: String) : RuntimeException(message)

fun <M : Any> MessageCodec<M>.encode(message: M, refs: Refs): ByteArray = WireOut(refs).also {
    write(message, it)
}.bytes()

fun <M : Any> MessageCodec<M>.decode(bytes: ByteArray, refs: Refs): M {
    val input = WireIn(bytes, refs)
    return try {
        read(input)
    } catch (short: EOFException) {
        throw WireException("a message ended after ${bytes.size} bytes, part way through what its codec reads")
            .apply { initCause(short) }
    }
}

/** One message's fields on their way out, in the order a codec writes them. */
class WireOut internal constructor(private val refs: Refs) {
    private val buffer = ByteArrayOutputStream()
    private val data = DataOutputStream(buffer)

    fun int(value: Int) = data.writeInt(value)

    fun long(value: Long) = data.writeLong(value)

    fun double(value: Double) = data.writeDouble(value)

    fun boolean(value: Boolean) = data.writeBoolean(value)

    /** UTF-8 with its length in front: `writeUTF` stops at 65,535 bytes and is not quite UTF-8. */
    fun string(value: String) = bytes(value.toByteArray(Charsets.UTF_8))

    fun bytes(value: ByteArray) {
        data.writeInt(value.size)
        data.write(value)
    }

    /** [value] when there is one, after a flag saying whether there is. */
    fun <T : Any> nullable(value: T?, write: (T) -> Unit) {
        boolean(value != null)
        if (value != null) write(value)
    }

    /** [ref], as an address; [codec] is what a message told to it is written with, wherever it is told from. */
    fun <M : Any> ref(ref: ActorRef<M>, codec: MessageCodec<M>) = address(refs.address(ref, codec))

    /** [reply], as an address; [answers] is what the answer to it is written with, on the node that answers. */
    fun <A : Any> reply(reply: Reply<A>, answers: MessageCodec<A>) = address(refs.address(reply, answers))

    private fun address(address: Address) {
        string(address.node)
        string(address.path)
        long(address.incarnation)
    }

    internal fun bytes(): ByteArray = buffer.toByteArray()
}

/** One message's fields on their way in, read in the order they were written. */
class WireIn internal constructor(bytes: ByteArray, private val refs: Refs) {
    private val data = DataInputStream(bytes.inputStream())

    fun int(): Int = data.readInt()

    fun long(): Long = data.readLong()

    fun double(): Double = data.readDouble()

    fun boolean(): Boolean = data.readBoolean()

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun bytes(): ByteArray {
        val size = data.readInt()
        if (size < 0) throw WireException("a message holds a field of $size bytes")
        return ByteArray(size).also(data::readFully)
    }

    fun <T : Any> nullable(read: () -> T): T? = if (boolean()) read() else null

    fun <M : Any> ref(codec: MessageCodec<M>): ActorRef<M> = refs.ref(address(), codec)

    fun <A : Any> reply(answers: MessageCodec<A>): Reply<A> = refs.reply(address(), answers)

    private fun address(): Address = Address(string(), string(), long())
}

/** Codecs for the answers most asks want, so that a protocol writes only its own messages. */
object Codecs {
    val int: MessageCodec<Int> = codec(WireOut::int, WireIn::int)
    val long: MessageCodec<Long> = codec(WireOut::long, WireIn::long)
    val double: MessageCodec<Double> = codec(WireOut::double, WireIn::double)
    val boolean: MessageCodec<Boolean> = codec(WireOut::boolean, WireIn::boolean)
    val string: MessageCodec<String> = codec(WireOut::string, WireIn::string)
    val bytes: MessageCodec<ByteArray> = codec(WireOut::bytes, WireIn::bytes)
    val unit: MessageCodec<Unit> = codec({ _, _ -> }, { })

    /** Each element with [element], after how many there are. */
    fun <A : Any> list(element: MessageCodec<A>): MessageCodec<List<A>> = codec(
        { out, list ->
            out.int(list.size)
            list.forEach { element.write(it, out) }
        },
        { input -> List(input.int()) { element.read(input) } },
    )

    private fun <A : Any> codec(write: (WireOut, A) -> Unit, read: (WireIn) -> A): MessageCodec<A> =
        object : MessageCodec<A> {
            override fun write(message: A, out: WireOut) = write(out, message)

            override fun read(input: WireIn): A = read(input)
        }
}
