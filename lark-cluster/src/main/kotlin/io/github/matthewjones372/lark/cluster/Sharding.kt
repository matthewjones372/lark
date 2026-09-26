package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.entities
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal object Sharding {
    /** Shards per kind unless a kind says otherwise; every node of a cluster must use the same number. */
    const val SHARDS = 256

    /** Messages a region keeps while it knows no owner for them; past this they are dead letters. */
    const val KEEP_AT_MOST = 10_000

    /** Times a message is passed between nodes that each think another owns it before it waits for the view. */
    const val MOST_HOPS = 3

    /** How long a message that ran out of hops waits before it is routed again. */
    val RETRY_AFTER = 100.milliseconds

    private val kinds = Regex("[A-Za-z0-9._-]+")

    fun path(kind: String): String {
        require(kinds.matches(kind)) { "a kind is letters, digits, '.', '_' and '-', was '$kind'" }
        return "/user/sharding-$kind"
    }
}

/**
 * What a region handles: a message for one of its kind's entities, the view it places them by, and the two halves of
 * a handoff: a node that has won a shard asks the others to [Release] it, and each says it has once it runs none of it.
 */
internal sealed interface Region<M : Any> {
    data class Envelope<M : Any>(val id: String, val hops: Int, val message: M) : Region<M>

    data class Release<M : Any>(val shard: Int, val from: Node) : Region<M>

    data class Released<M : Any>(val shard: Int, val by: Node) : Region<M>

    data class Viewed<M : Any>(val view: View) : Region<M>

    /** A region's own timer, to route again what ran out of hops. */
    class Retry<M : Any> : Region<M>
}

private const val ENVELOPE = 1
private const val RELEASE = 2
private const val RELEASED = 3

/** What crosses between regions: an envelope holds the id, then the message in the kind's own codec. */
private class RegionCodec<M : Any>(private val codec: MessageCodec<M>) : MessageCodec<Region<M>> {
    override fun write(message: Region<M>, out: WireOut) = when (message) {
        is Region.Envelope -> {
            out.int(ENVELOPE)
            out.string(message.id)
            out.int(message.hops)
            codec.write(message.message, out)
        }

        is Region.Release -> {
            out.int(RELEASE)
            out.int(message.shard)
            out.string(message.from.toString())
        }

        is Region.Released -> {
            out.int(RELEASED)
            out.int(message.shard)
            out.string(message.by.toString())
        }

        is Region.Viewed, is Region.Retry -> error("$message never leaves its node")
    }

    override fun read(input: WireIn): Region<M> = when (val tag = input.int()) {
        ENVELOPE -> Region.Envelope(id = input.string(), hops = input.int(), message = codec.read(input))
        RELEASE -> Region.Release(input.int(), Node.parse(input.string()))
        RELEASED -> Region.Released(input.int(), Node.parse(input.string()))
        else -> error("no region message has the tag $tag")
    }
}

/** The entities of one kind, spread across the cluster's `Up` members by [Placement]. */
class Sharded<M : Any> internal constructor(val kind: String, private val region: ActorRef<Region<M>>) {
    /** The entity [id], wherever it runs now: a ref that stays good while it moves between nodes. */
    fun entity(id: String): ActorRef<M> = ShardedRef(region, id)
}

private class ShardedRef<M : Any>(private val region: ActorRef<Region<M>>, private val id: String) : ActorRef<M> {
    override val address = Address(region.address.node, "${region.address.path}/$id", 0)

    override fun tell(message: M) = region.tell(Region.Envelope(id, 0, message))

    override fun equals(other: Any?) = other is ShardedRef<*> && other.region == region && other.id == id

    override fun hashCode() = region.hashCode() * 31 + id.hashCode()

    override fun toString() = "ShardedRef(${address.path})"
}

/**
 * The entities of [kind], one actor per id made by [entity], each run on the `Up` member that owns its shard and
 * stopped once it has had nothing for [passivateAfter]. Every node that runs the cluster runs this too, with the same
 * [kind], [codec] and [shards]; a message told on any of them reaches the owner through its region.
 */
fun <M : Any, S, E> Cluster.sharding(
    kind: String,
    codec: MessageCodec<M>,
    passivateAfter: Duration,
    shards: Int = Sharding.SHARDS,
    entity: (id: String) -> Behaviour<M, S, E>,
): Sharded<M> {
    val path = Sharding.path(kind)
    require(shards > 0) { "shards must be positive, was $shards" }
    val wire = RegionCodec(codec)
    val placing = Placing(this, kind, shards, wire, path) { ctx, shard ->
        ctx.spawn("shard-$shard", entities(passivateAfter, entity = entity))
    }
    val region = flock.spawn(
        path.removePrefix("/user/"),
        behaviour<Region<M>, Unit>(Unit) { ctx, _, step -> stay().also { placing.step(ctx, step) } }
            .onSignal { ctx, _, signal ->
                if (signal is Signal.Terminated) placing.ended(ctx, signal.ref)
                stay()
            },
    )
    remote.expose(region, wire)
    onView { region.tell(Region.Viewed(it)) }
    return Sharded(kind, region)
}
