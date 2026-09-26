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
 * node is which, or a test standing in for it.
 */
interface Refs {
    fun address(ref: ActorRef<*>): Address

    fun address(reply: Reply<*>): Address

    fun <M : Any> ref(address: Address): ActorRef<M>

    fun <A : Any> reply(address: Address): Reply<A>
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

    fun ref(ref: ActorRef<*>) = address(refs.address(ref))

    fun reply(reply: Reply<*>) = address(refs.address(reply))

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

    fun <M : Any> ref(): ActorRef<M> = refs.ref(address())

    fun <A : Any> reply(): Reply<A> = refs.reply(address())

    private fun address(): Address = Address(string(), string(), long())
}
