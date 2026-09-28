package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Entities
import io.github.matthewjones372.lark.actor.Producer
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.durableProducer
import io.github.matthewjones372.lark.actor.entities
import io.github.matthewjones372.lark.actor.entity
import io.github.matthewjones372.lark.actor.gauge
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.producer
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.remote.outbox
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal object Sharding {
    /** Shards per kind unless a kind says otherwise; every node of a cluster must use the same number. */
    const val SHARDS = 256

    /** Messages a region keeps while it knows no owner for them; past this they are dead letters. */
    const val KEEP_AT_MOST = 10_000

    /** Times a message is passed between nodes that each think another owns it before it waits for the view. */
    const val MOST_HOPS = 3

    /** How long a message that ran out of hops waits before it is routed again. */
    val RETRY_AFTER = 100.milliseconds

    /** How soon a region offers a busy host what it kept for it again (spec 0095). */
    val DRAIN_AFTER = 10.milliseconds

    /** How often a region asks again for a shard it won that a member has not released: that ask may have been lost. */
    val ASK_AGAIN_EVERY = 1.seconds

    private val kinds = Regex("[A-Za-z0-9._-]+")

    fun path(kind: String, prefix: String = "sharding"): String {
        require(kinds.matches(kind)) { "a name is letters, digits, '.', '_' and '-', was '$kind'" }
        return "/user/$prefix-$kind"
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

    data class Viewed<M : Any>(val view: View, val moved: Map<Int, Incarnation> = emptyMap()) : Region<M>

    /** A region's own timer, to route again what ran out of hops. */
    class Retry<M : Any> : Region<M>

    /** A region's own timer, to hand busy hosts what it kept for them (spec 0095). */
    class Drain<M : Any> : Region<M>

    /** A region's own timer, to ask again for the shards it won that a member has not yet released. */
    class AskAgain<M : Any> : Region<M>
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

        is Region.Viewed, is Region.Retry, is Region.Drain, is Region.AskAgain ->
            error("$message never leaves its node")
    }

    override fun read(input: WireIn): Region<M> = when (val tag = input.int()) {
        ENVELOPE -> Region.Envelope(id = input.string(), hops = input.int(), message = codec.read(input))
        RELEASE -> Region.Release(input.int(), Node.parse(input.string()))
        RELEASED -> Region.Released(input.int(), Node.parse(input.string()))
        else -> error("no region message has the tag $tag")
    }
}

/** The entities of one kind, spread across the cluster's `Up` members by [Placement]. */
class Sharded<M : Any> internal constructor(
    val kind: String,
    private val region: ActorRef<Region<M>>,
    private val flock: Flock<*>,
    private val leaveWithin: Duration,
    private val codec: MessageCodec<M>,
) {
    /** The entity [id], wherever it runs now: a ref that stays good while it moves between nodes. */
    fun entity(id: String): ActorRef<M> = ShardedRef(region, id)

    /**
     * A producer on this node that sends to these entities at least once (spec 0079): a command lost to a move or a
     * passivation is sent again every [resendAfter] until its entity confirms it. The kind's codec writes each
     * command's `Delivery` with `WireOut.delivery`, and its entities are wrapped in `delivered`; a persistent one
     * drops the duplicates a resend makes. See `Flock.producer` for [keep] and [within]. When the node's flock
     * closes, the producer waits for its commands to be confirmed as long as the cluster waits to leave, and before it.
     *
     * A [durable] producer keeps its commands in the flock's journal rather than in memory (spec 0085), so one whose
     * node crashes loses none: started again with the same [producerId], on any node, it sends what is unconfirmed.
     * Its commands implement `Delivered.redeliver`, and `send` returns once each is written.
     */
    @Suppress("LongParameterList")
    fun reliable(
        producerId: String,
        resendAfter: Duration = 2.seconds,
        keep: Int = 1_000,
        within: Duration = 5.seconds,
        durable: Boolean = false,
    ): Producer<M> {
        val id = "$kind-$producerId"
        return if (durable) {
            flock.durableProducer(id, codec.outbox(), resendAfter, keep, within, leaveWithin, ::entity)
        } else {
            flock.producer(id, resendAfter, keep, within, leaveWithin, ::entity)
        }
    }
}

internal class ShardedRef<M : Any>(private val region: ActorRef<Region<M>>, private val id: String) : ActorRef<M> {
    override val address = Address(region.address.node, "${region.address.path}/$id", 0)

    override fun tell(message: M) = region.tell(Region.Envelope(id, 0, message))

    override fun equals(other: Any?) = other is ShardedRef<*> && other.region == region && other.id == id

    override fun hashCode() = region.hashCode() * 31 + id.hashCode()

    override fun toString() = "ShardedRef(${address.path})"
}

/**
 * The entities of [kind], one actor per id made by [entity], each run on the `Up` member that owns its shard and
 * stopped once it has had nothing for [passivateAfter]. Every node that runs the cluster runs this too, with the same
 * [kind], [codec] and [shards]; a message told on any of them reaches the owner through its region. With a [role],
 * only members started with it host shards (spec 0083); the rest route to them, and while none is up, what is told
 * is kept. With [rebalance], the leader moves shards off members busier than the rest (spec 0090); without, a shard
 * stays with its hash owner.
 */
fun <M : Any, S, E> Cluster.sharding(
    kind: String,
    codec: MessageCodec<M>,
    passivateAfter: Duration,
    shards: Int = Sharding.SHARDS,
    role: String? = null,
    rebalance: Rebalance? = null,
    entity: (id: String) -> Behaviour<M, S, E>,
): Sharded<M> {
    val path = Sharding.path(kind)
    require(shards > 0) { "shards must be positive, was $shards" }
    // Every shard's manager adds to one count of this kind's entities running here (spec 0081).
    val running = flock.gauge("lark.sharding.entities", "kind" to kind)
    val counting = ReentrantLock()
    var count = 0L
    val onRunning = { delta: Int -> counting.withLock { running.set((count + delta).also { count = it }.toDouble()) } }
    val meter = rebalance?.let { LoadMeter(kind, it, shards, role).also(meters::add) }
    val hosting = Hosting<M, Entities<M>>(
        eager = false,
        owner = { shard, members, moved -> Placement.owner(kind, shard, members.holding(role), moved[shard]) },
        start = { ctx, shard ->
            val counted = { delta: Int -> onRunning(delta).also { meter?.running(shard, delta) } }
            ctx.spawn("shard-$shard", entities(passivateAfter, onRunning = counted, entity = entity))
        },
        // Counted once each message is handed to its entity here, which is the load a rebalance weighs (spec 0090).
        target = { manager, id ->
            meter?.handled(Placement.shardOf(id, shards))
            manager.entity(id)
        },
    )
    return Sharded(kind, region(path, codec, shards, hosting, kind), flock, leaveWithin, codec)
}

/** A region at [path] on this node, reachable from the others at the same path. */
internal fun <M : Any, H : Any> Cluster.region(
    path: String,
    codec: MessageCodec<M>,
    shards: Int,
    hosting: Hosting<M, H>,
    kind: String,
): ActorRef<Region<M>> {
    val wire = RegionCodec(codec)
    val meters = RegionMeters(
        shards = flock.gauge("lark.sharding.shards", "kind" to kind),
        buffered = flock.gauge("lark.sharding.buffered", "kind" to kind),
    )
    val placing = Placing(this, shards, wire, path, hosting, meters)
    val region = flock.spawn(
        path.removePrefix("/user/"),
        behaviour<Region<M>, Unit>(Unit) { ctx, _, step -> stay().also { placing.step(ctx, step) } }
            .onSignal { ctx, _, signal ->
                when (signal) {
                    is Signal.Terminated -> placing.ended(ctx, signal.ref)
                    Signal.Stopping -> placing.stopping(ctx)
                }
                stay()
            },
    )
    remote.expose(region, wire)
    onView { region.tell(Region.Viewed(it, balance.moved[kind].orEmpty())) }
    return region
}

/** The members that may host what [role] names: all of them when it is null. */
internal fun List<Member>.holding(role: String?): List<Member> = if (role == null) this else filter { role in it.roles }
