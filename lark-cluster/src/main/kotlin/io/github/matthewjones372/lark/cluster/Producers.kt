package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.Delivered
import io.github.matthewjones372.lark.actor.Delivery
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.delivered
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.producer
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.remote.delivery
import io.github.matthewjones372.lark.logWarn
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/** One life of a node, as a durable producer's id carries it: its name, and its uid in base 36. */
internal data class Life(val node: Node, val uid: Long) {
    override fun toString() = "${node.name}-${uid.toULong().toString(radix = 36)}"
}

/** A durable producer as the registry lists it: the kind it sends to, the life that made it, and the one running it. */
internal data class Listed(val kind: String, val owner: Life, val runner: Life)

/** What the `lark-producers` singleton handles. */
internal sealed interface Registry {
    /** [owner] runs the durable producer [producer], which sends to [kind]'s entities. */
    data class Register(
        val producer: String,
        val kind: String,
        val owner: Life,
        override val delivery: Delivery,
    ) : Registry,
        Delivered
}

private typealias Registered = Map<String, Listed>

/** What the registry writes: a producer listed. */
private data class Enlisted(val producer: String, val listed: Listed)

private const val REGISTRY = "lark-producers"

/**
 * This node's part in the registry of durable producers (spec 0099): the singleton itself, which runs on one node of
 * the cluster, and this node's registrations, sent to it at least once. [listed] is what the singleton lists, on the
 * node it runs on.
 */
internal class Producers(private val cluster: Cluster) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    var listed: Registered = emptyMap()
        private set

    private val registry = cluster.singleton(REGISTRY, RegistryCodec) { registry(::publish) }

    private val registrations by lazy {
        cluster.flock.producer("$REGISTRY-${cluster.life}", drainWithin = cluster.leaveWithin) {
            registry
        }
    }

    /** Lists [producer], of [kind]'s entities, as this life's. */
    fun register(producer: String, kind: String) {
        registrations.send(REGISTRY) { Registry.Register(producer, kind, cluster.life, it) }
            .onLeft { logWarn("$producer was not registered: $REGISTRY keeps as many registrations as it may") }
    }

    private fun publish(now: Registered) = lock.withLock {
        listed = now
        changed.signalAll()
    }

    /** Waits up to [within] for [listed] to be one that [until] holds for; whether it came. */
    fun await(within: Duration, until: (Registered) -> Boolean): Boolean = lock.withLock {
        var left = within.inWholeNanoseconds
        while (!until(listed)) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        true
    }
}

/** The registry: every durable producer and the life that runs it, told to [listed] as it changes. */
private fun registry(listed: (Registered) -> Unit) = delivered(
    persistent<Registry, Enlisted, Registered>(
        id = PersistenceId(REGISTRY, REGISTRY),
        empty = emptyMap(),
        codec = EnlistedCodec,
        command = { _, registered, message ->
            when (message) {
                is Registry.Register -> {
                    val listing = Listed(message.kind, message.owner, message.owner)
                    if (message.producer in registered) {
                        none()
                    } else {
                        persist(Enlisted(message.producer, listing)).then(listed)
                    }
                }
            }
        },
        event = { registered, enlisted -> registered + (enlisted.producer to enlisted.listed) },
    ),
)

private fun WireOut.life(life: Life) {
    string(life.node.toString())
    long(life.uid)
}

private fun WireIn.life() = Life(Node.parse(string()), long())

private object RegistryCodec : MessageCodec<Registry> {
    override fun write(message: Registry, out: WireOut) = when (message) {
        is Registry.Register -> {
            out.string(message.producer)
            out.string(message.kind)
            out.life(message.owner)
            out.delivery(message.delivery)
        }
    }

    override fun read(input: WireIn): Registry =
        Registry.Register(input.string(), input.string(), input.life(), input.delivery())
}

private object EnlistedCodec : EventCodec<Enlisted> {
    override fun encode(event: Enlisted): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeUTF(event.producer)
            data.writeUTF(event.listed.kind)
            listOf(event.listed.owner, event.listed.runner).forEach { life ->
                data.writeUTF(life.node.toString())
                data.writeLong(life.uid)
            }
        }
    }.toByteArray()

    override fun decode(bytes: ByteArray): Enlisted = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        fun life() = Life(Node.parse(data.readUTF()), data.readLong())
        Enlisted(data.readUTF(), Listed(data.readUTF(), life(), life()))
    }
}
