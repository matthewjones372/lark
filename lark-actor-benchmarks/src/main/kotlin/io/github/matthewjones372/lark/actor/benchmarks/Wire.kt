package io.github.matthewjones372.lark.actor.benchmarks

import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.serialization.SerializerWithStringManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

// The cluster benchmarks' messages, once per runtime with the same fields, each written by hand field by field: a
// lark `MessageCodec` on one side and a Pekko serializer on the other, over the same `DataOutputStream`.

internal sealed interface Shard

/** Counts itself on the burst's latch. */
internal data class Bump(val n: Int) : Shard

/** Answered with [n]. */
internal data class Bounce(val n: Int, val reply: Reply<Int>) : Shard

/** Answered with the name of the node the entity runs on. */
internal data class Locate(val reply: Reply<String>) : Shard

internal val shardCodec = object : MessageCodec<Shard> {
    override fun write(message: Shard, out: WireOut) = when (message) {
        is Bump -> {
            out.int(1)
            out.int(message.n)
        }

        is Bounce -> {
            out.int(2)
            out.int(message.n)
            out.reply(message.reply, Codecs.int)
        }

        is Locate -> {
            out.int(3)
            out.reply(message.reply, Codecs.string)
        }
    }

    override fun read(input: WireIn): Shard = when (val tag = input.int()) {
        1 -> Bump(input.int())
        2 -> Bounce(input.int(), input.reply(Codecs.int))
        3 -> Locate(input.reply(Codecs.string))
        else -> error("no shard message has the tag $tag")
    }
}

/** What [WireSerializer] writes: every Pekko message a cluster benchmark sends between nodes. */
interface PekkoWire

sealed interface PekkoShard : PekkoWire

data class PekkoBump(val n: Int) : PekkoShard

data class PekkoBounce(val n: Int, val replyTo: ActorRef<Int>) : PekkoShard

data class PekkoLocate(val replyTo: ActorRef<String>) : PekkoShard

/** Pekko's side of the benchmarks' codecs: a ref crosses as the string Pekko's resolver makes of it. */
class WireSerializer(system: ExtendedActorSystem) : SerializerWithStringManifest() {
    private val refs = ActorRefResolver.get(Adapter.toTyped(system))

    override fun identifier(): Int = IDENTIFIER

    override fun manifest(o: Any): String = when (o) {
        is PekkoBump -> "B"
        is PekkoBounce -> "E"
        is PekkoLocate -> "L"
        else -> throw IllegalArgumentException("no manifest for ${o.javaClass}")
    }

    override fun toBinary(o: Any): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        when (o) {
            is PekkoBump -> out.writeInt(o.n)

            is PekkoBounce -> {
                out.writeInt(o.n)
                out.string(refs.toSerializationFormat(o.replyTo))
            }

            is PekkoLocate -> out.string(refs.toSerializationFormat(o.replyTo))

            else -> throw IllegalArgumentException("cannot write ${o.javaClass}")
        }
        return buffer.toByteArray()
    }

    override fun fromBinary(bytes: ByteArray, manifest: String): Any {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return when (manifest) {
            "B" -> PekkoBump(input.readInt())
            "E" -> PekkoBounce(input.readInt(), refs.resolveActorRef(input.string()))
            "L" -> PekkoLocate(refs.resolveActorRef(input.string()))
            else -> throw IllegalArgumentException("no message has the manifest $manifest")
        }
    }

    private companion object {
        const val IDENTIFIER = 920_092
    }
}

/** As lark's `WireOut.string`: UTF-8 with its length in front. */
private fun DataOutputStream.string(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.string(): String = String(readNBytes(readInt()), Charsets.UTF_8)
