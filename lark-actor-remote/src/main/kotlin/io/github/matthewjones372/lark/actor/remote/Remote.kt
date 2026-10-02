package io.github.matthewjones372.lark.actor.remote

import arrow.core.nonFatalOrThrow
import io.github.matthewjones372.lark.Carriers
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.DeadLetter
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.Watchable
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.counter
import io.github.matthewjones372.lark.actor.deadLetter
import io.github.matthewjones372.lark.actor.gauge
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.tagMetrics
import io.github.matthewjones372.lark.actor.watch
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logger
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a reply waits for its answer before this node forgets it: longer than any ask should. */
private val FORGET_REPLIES_AFTER = TimeUnit.MINUTES.toNanos(10)

/** How many replies are registered between looks for ones to forget. */
private const val SWEEP_EVERY = 1024L

private const val REPLIES = "/temp/reply-"
private const val WATCH = "/system/watch"
private const val TERMINATED = "/system/terminated"

/** A watched actor's path and incarnation, as the watch and its end carry them. */
private fun target(address: Address): ByteArray =
    ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            it.writeUTF(address.path)
            it.writeLong(address.incarnation)
        }
    }.toByteArray()

/** The address [target] wrote, on the node [on]. */
private fun targetOf(on: Node, payload: ByteArray): Address =
    DataInputStream(payload.inputStream()).use { Address(on.toString(), it.readUTF(), it.readLong()) }

/**
 * This flock as a node named [name], listening on [host] and [port], until the flock closes. An actor of this node is
 * reached from another only once it is [RemoteNode.expose]d with the codec its messages cross in, or when a ref to it
 * crosses inside a message, which exposes it with the codec the message gave for it. With [tls], every connection
 * to and from this node is TLS, and only nodes whose certificates name them are let in (spec 0073).
 */
fun <F> Flock<F>.node(
    name: String,
    port: Int,
    host: String = "127.0.0.1",
    unreachableAfter: Duration = 10.seconds,
    tls: Tls? = null,
): RemoteNode {
    // Every metric of this flock names the node it runs, so several nodes in one JVM are told apart (spec 0081).
    tagMetrics("node" to name)
    val node = RemoteNode(Node(name, host, port), this, unreachableAfter, tls)
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

/** What a dead letter holds when the frame it came in could not be read: no codec was known for its path. */
class UnreadMessage(val bytes: ByteArray)

/**
 * A node: the actors it exposes, the replies it waits on, the watches on other nodes' actors, and the transport to
 * the others. A watch ends with `Terminated` when the actor stops, and when its node has been unreachable for
 * [unreachableAfter], or, once a membership has [takeOverWatches], when the membership says the node is gone.
 */
class RemoteNode internal constructor(
    val self: Node,
    private val flock: Flock<*>,
    private val unreachableAfter: Duration,
    tls: Tls? = null,
) {
    /** One peer's instruments, looked up once: counting a frame is then a map hit and no allocation. */
    private inner class PeerMetrics(peer: Node) {
        private val name = peer.toString()
        private fun byLane(metric: String, vararg tags: Pair<String, String>) =
            Lane.entries.associateWith { flock.counter(metric, "peer" to name, *tags, "lane" to it.name.lowercase()) }
        val sent = byLane("lark.remote.frames", "direction" to "out")
        val received = byLane("lark.remote.frames", "direction" to "in")
        val dropped = byLane("lark.remote.dropped")
        val connected = flock.gauge("lark.remote.connected", "peer" to name)
    }

    private val measured = ConcurrentHashMap<Node, PeerMetrics>()

    private fun measure(peer: Node): PeerMetrics = measured.computeIfAbsent(peer) { PeerMetrics(it) }

    /** Hands [frame] to the transport for [peer], on its path's lane, counting it. */
    private fun send(peer: Node, frame: Frame) {
        val lane = laneOf(frame.path)
        measure(peer).sent.getValue(lane).increment()
        transport.send(peer, frame, lane)
    }

    // The paths whose frames cross on the control lane (spec 0104): watches and their ends always, and whatever is
    // exposed or reached as control. Every node exposes the cluster's own actor at the same path, so both sides agree.
    private val control = ConcurrentHashMap.newKeySet<String>()

    private fun laneOf(path: String): Lane =
        if (path == WATCH || path == TERMINATED || path in control) Lane.Control else Lane.Data

    private val exposed = ConcurrentHashMap<String, Exposed<*>>()
    private val pending = ConcurrentHashMap<String, Pending<*>>()
    private val replies = AtomicLong()
    private val log = logger.get()
    private val time = clock.get()
    private val refs = NodeRefs()

    // Watches this node holds on other nodes' actors, and the watches other nodes hold on this one's.
    private val watching = ConcurrentHashMap<Address, CopyOnWriteArrayList<() -> Unit>>()
    private val watched = ConcurrentHashMap.newKeySet<Pair<Node, Address>>()

    // The peers with a connection up now, on either lane, and those whose unreachable timer is running.
    private val up = ConcurrentHashMap.newKeySet<Pair<Node, Lane>>()
    private val timing = ConcurrentHashMap.newKeySet<Node>()

    @Volatile
    private var membershipEndsWatches = false

    internal val transport = Transport(self, Inbound(), tls = tls)

    /** Lets other nodes tell [ref] messages written with [codec], at its path. */
    fun <M : Any> expose(ref: ActorRef<M>, codec: MessageCodec<M>, lane: Lane = Lane.Data) {
        if (lane == Lane.Control) control += ref.address.path
        exposed[ref.address.path] = Exposed(ref, codec)
    }

    /**
     * The actor at [address] on another node, told messages written with [codec]. An incarnation of 0 is whichever
     * actor is at the path when a message arrives; any other is that one actor, and a message for an earlier one is
     * not delivered to its successor.
     */
    fun <M : Any> remote(address: Address, codec: MessageCodec<M>, lane: Lane? = null): ActorRef<M> {
        if (lane == Lane.Control) control += address.path
        return refs.ref(address, codec)
    }

    /**
     * Hands ending watches on other nodes' actors to a membership, which knows better than a timer when a node is
     * gone: from now on an unreachable peer ends none, and what this returns ends every watch on the peer it is given.
     */
    fun takeOverWatches(): (Node) -> Unit {
        membershipEndsWatches = true
        return { peer -> watching.keys.filter { it.node == peer.toString() }.forEach(::terminated) }
    }

    private fun here(address: Address): Address = Address(self.toString(), address.path, address.incarnation)

    private class Exposed<M : Any>(val ref: ActorRef<M>, val codec: MessageCodec<M>) {
        fun read(payload: ByteArray, refs: Refs): M = codec.decode(payload, refs)

        fun deliver(payload: ByteArray, refs: Refs) = ref.tell(read(payload, refs))
    }

    /** Asks [peer] to say when the actor at [address] ends; [notify] runs then, or once [peer] is unreachable. */
    private fun watch(peer: Node, address: Address, notify: () -> Unit) {
        watching.computeIfAbsent(address) { CopyOnWriteArrayList() } += notify
        send(peer, Frame(WATCH, 0, target(address)))
    }

    /** Another node watches [address] here: it hears once the actor there ends, or at once if none is there. */
    private fun watchedFrom(peer: Node, address: Address) {
        if (!watched.add(peer to address)) return
        val actor = exposed[address.path]?.ref
            ?.takeIf { address.incarnation == 0L || it.address.incarnation == address.incarnation }
        val terminated = Frame(TERMINATED, 0, target(address))
        if (actor == null) return send(peer, terminated)
        Thread.ofVirtual().name("lark-remote-watch").start {
            flock.watch(actor).await()
            watched -= peer to address
            send(peer, terminated)
        }
    }

    private fun terminated(address: Address) = watching.remove(address)?.forEach { it() }

    /** [peer] went: if it has not come back after [unreachableAfter], every watch on it ends. */
    private fun maybeUnreachable(peer: Node) {
        if (membershipEndsWatches || !timing.add(peer)) return
        Thread.ofVirtual().name("lark-remote-unreachable").start {
            try {
                time.sleep(unreachableAfter)
            } catch (_: InterruptedException) {
                return@start
            } finally {
                timing -= peer
            }
            if (up.none { it.first == peer }) watching.keys.filter { it.node == peer.toString() }.forEach(::terminated)
        }
    }

    private class Pending<A : Any>(val reply: Reply<A>, val answers: MessageCodec<A>, val since: Long) {
        fun answer(payload: ByteArray, refs: Refs) = reply(answers.decode(payload, refs))
    }

    /**
     * What arrives from other nodes: a message for an exposed actor, the answer to a reply sent from here, or a watch
     * and its end. What is sent from here and cannot go is a dead letter.
     */
    private inner class Inbound : Listener {
        // A frame this node cannot read ends only that frame: the connection, and the frames after it, go on.
        @Suppress("TooGenericExceptionCaught")
        override fun received(from: Node, frame: Frame) {
            measure(from).received.getValue(laneOf(frame.path)).increment()
            try {
                when {
                    frame.path.startsWith(REPLIES) -> pending.remove(frame.path)?.answer(frame.payload, refs)
                    frame.path == WATCH -> watchedFrom(from, targetOf(from, frame.payload))
                    frame.path == TERMINATED -> terminated(targetOf(from, frame.payload))
                    else -> deliver(frame)
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

        private fun deliver(frame: Frame) {
            val recipient = Address(self.toString(), frame.path, frame.incarnation)
            val actor = exposed[frame.path]
            when {
                actor == null ->
                    flock.deadLetter(DeadLetter(recipient, UnreadMessage(frame.payload), DeadLetter.Why.NoSuchActor))

                frame.incarnation != 0L && actor.ref.address.incarnation != frame.incarnation ->
                    flock.deadLetter(DeadLetter(recipient, actor.read(frame.payload, refs), DeadLetter.Why.NoSuchActor))

                // Told inside what it carried, so the local tell carries it on to the handler (spec 0122).
                else -> Carriers.within(frame.carried) { actor.deliver(frame.payload, refs) }
            }
        }

        override fun connected(peer: Node, lane: Lane) {
            measure(peer).connected.set(1.0)
            up += peer to lane
        }

        override fun disconnected(peer: Node, lane: Lane) {
            up -= peer to lane
            if (up.none { it.first == peer }) measure(peer).connected.set(0.0)
            maybeUnreachable(peer)
        }

        override fun dropped(peer: Node, frame: Frame) {
            measure(peer).dropped.getValue(laneOf(frame.path)).increment()
            val recipient = Address(peer.toString(), frame.path, frame.incarnation)
            val message = frame.message ?: UnreadMessage(frame.payload)
            flock.deadLetter(DeadLetter(recipient, message, DeadLetter.Why.Unreachable))
            maybeUnreachable(peer)
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

    /**
     * An actor on another node, told through the transport, and watched by asking its node. Equal to any ref with the
     * same address.
     */
    private inner class RemoteRef<M : Any>(
        private val peer: Node,
        override val address: Address,
        private val codec: MessageCodec<M>,
    ) : ActorRef<M>, Watchable {
        override fun tell(message: M) =
            send(peer, Frame(address.path, address.incarnation, codec.encode(message, refs), message, Carriers.capture()))

        override fun onTerminated(notify: () -> Unit) = watch(peer, address, notify)

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
            send(peer, Frame(address.path, address.incarnation, answers.encode(answer, refs), answer))
    }
}
