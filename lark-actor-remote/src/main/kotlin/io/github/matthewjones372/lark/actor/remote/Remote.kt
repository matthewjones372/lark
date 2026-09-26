package io.github.matthewjones372.lark.actor.remote

import arrow.core.nonFatalOrThrow
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** How long a reply waits for its answer before this node forgets it: longer than any ask should. */
private val FORGET_REPLIES_AFTER = TimeUnit.MINUTES.toNanos(10)

/** How many replies are registered between looks for ones to forget. */
private const val SWEEP_EVERY = 1024L

private const val REPLIES = "/temp/reply-"

/**
 * This flock as a node named [name], listening on [host] and [port], until the flock closes. An actor of this node is
 * reached from another only once it is [RemoteNode.expose]d with the codec its messages cross in, or when a ref to it
 * crosses inside a message, which exposes it with the codec the message gave for it.
 */
fun <F> Flock<F>.node(name: String, port: Int, host: String = "127.0.0.1"): RemoteNode {
    val node = RemoteNode(Node(name, host, port))
    node.transport.listen()
    // The flock has no hook of its own for close, and an actor's Stopping is one: the node closes with it.
    spawn(
        "remote-$name",
        behaviour<Unit, Unit>(Unit) { _, _, _ -> stay() }.onSignal { _, _, signal ->
            if (signal == Signal.Stopping) node.transport.close()
            stay()
        },
    )
    return node
}

/** A node: the actors it exposes, the replies it waits on, and the transport to the others. */
class RemoteNode internal constructor(val self: Node) {

    private val exposed = ConcurrentHashMap<String, Exposed<*>>()
    private val pending = ConcurrentHashMap<String, Pending<*>>()
    private val replies = AtomicLong()
    private val log = logger.get()
    private val time = clock.get()
    private val refs = NodeRefs()

    internal val transport = Transport(self, Inbound())

    /** Lets other nodes tell [ref] messages written with [codec], at its path. */
    fun <M : Any> expose(ref: ActorRef<M>, codec: MessageCodec<M>) {
        exposed[ref.address.path] = Exposed(ref, codec)
    }

    /**
     * The actor at [address] on another node, told messages written with [codec]. An incarnation of 0 is whichever
     * actor is at the path when a message arrives; any other is that one actor, and a message for an earlier one is
     * not delivered to its successor.
     */
    fun <M : Any> remote(address: Address, codec: MessageCodec<M>): ActorRef<M> = refs.ref(address, codec)

    private fun here(address: Address): Address = Address(self.toString(), address.path, address.incarnation)

    private class Exposed<M : Any>(val ref: ActorRef<M>, val codec: MessageCodec<M>) {
        fun deliver(payload: ByteArray, refs: Refs) = ref.tell(codec.decode(payload, refs))
    }

    private class Pending<A : Any>(val reply: Reply<A>, val answers: MessageCodec<A>, val since: Long) {
        fun answer(payload: ByteArray, refs: Refs) = reply(answers.decode(payload, refs))
    }

    /** What arrives from other nodes: a message for an exposed actor, or the answer to a reply sent from here. */
    private inner class Inbound : Listener {
        // A frame this node cannot read ends only that frame: the connection, and the frames after it, go on.
        @Suppress("TooGenericExceptionCaught")
        override fun received(from: Node, frame: Frame) {
            try {
                if (frame.path.startsWith(REPLIES)) {
                    pending.remove(frame.path)?.answer(frame.payload, refs)
                } else {
                    exposed[frame.path]
                        ?.takeIf { frame.incarnation == 0L || it.ref.address.incarnation == frame.incarnation }
                        ?.deliver(frame.payload, refs)
                }
            } catch (unreadable: Throwable) {
                unreadable.nonFatalOrThrow()
                log.log(
                    LogLine(
                        LogLevel.Warn,
                        "lark-actor-remote: a frame from $from for ${frame.path} could not be read: $unreadable",
                        time.now(),
                        unreadable,
                    ),
                )
            }
        }
    }

    /** Refs as this node writes and reads them: its own actors under its own name, and the rest as they came. */
    private inner class NodeRefs : Refs {
        override fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>): Address =
            if (ref is RemoteRef<*>) {
                ref.address
            } else {
                exposed.putIfAbsent(ref.address.path, Exposed(ref, codec))
                here(ref.address)
            }

        override fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>): Address =
            if (reply is RemoteReply<*>) {
                reply.address
            } else {
                val count = replies.incrementAndGet()
                if (count % SWEEP_EVERY == 0L) forgetOldReplies()
                val path = "$REPLIES$count"
                pending[path] = Pending(reply, answers, System.nanoTime())
                Address(self.toString(), path, 0)
            }

        override fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M> {
            @Suppress("UNCHECKED_CAST")
            val local = exposed[address.path]?.ref.takeIf { address.node == self.toString() } as ActorRef<M>?
            return local ?: RemoteRef(Node.parse(address.node), address, codec)
        }

        override fun <A : Any> reply(address: Address, answers: MessageCodec<A>): Reply<A> =
            RemoteReply(Node.parse(address.node), address, answers)

        private fun forgetOldReplies() {
            val now = System.nanoTime()
            pending.entries.removeIf { now - it.value.since > FORGET_REPLIES_AFTER }
        }
    }

    /** An actor on another node, told through the transport. Equal to any ref with the same address. */
    private inner class RemoteRef<M : Any>(
        private val peer: Node,
        override val address: Address,
        private val codec: MessageCodec<M>,
    ) : ActorRef<M> {
        override fun tell(message: M) =
            transport.send(peer, Frame(address.path, address.incarnation, codec.encode(message, refs)))

        override fun equals(other: Any?) = other is ActorRef<*> && other.address == address

        override fun hashCode() = address.hashCode()

        override fun toString() = "RemoteRef(${address.node}${address.path}#${address.incarnation})"
    }

    /** A reply waited on by another node: answering it sends the answer there. */
    private inner class RemoteReply<A : Any>(
        private val peer: Node,
        override val address: Address,
        private val answers: MessageCodec<A>,
    ) : Reply<A> {
        override fun invoke(answer: A) =
            transport.send(peer, Frame(address.path, address.incarnation, answers.encode(answer, refs)))
    }
}
