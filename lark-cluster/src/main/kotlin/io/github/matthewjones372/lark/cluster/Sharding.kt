package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Ctx
import io.github.matthewjones372.lark.actor.DeadLetter
import io.github.matthewjones372.lark.actor.Entities
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.deadLetter
import io.github.matthewjones372.lark.actor.entities
import io.github.matthewjones372.lark.actor.entity
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import kotlin.time.Duration

internal object Sharding {
    /** Shards per kind unless a kind says otherwise; every node of a cluster must use the same number. */
    const val SHARDS = 256

    /** Messages a region keeps while it knows no owner for them; past this they are dead letters. */
    const val KEEP_AT_MOST = 10_000

    /** Times a message is passed between nodes that each think another owns it before it waits for the view. */
    const val MOST_HOPS = 3

    private val kinds = Regex("[A-Za-z0-9._-]+")

    fun path(kind: String): String {
        require(kinds.matches(kind)) { "a kind is letters, digits, '.', '_' and '-', was '$kind'" }
        return "/user/sharding-$kind"
    }
}

/** What a region handles: a message for one of its kind's entities, or the view it places them by. */
internal sealed interface Region<M : Any> {
    data class Envelope<M : Any>(val id: String, val hops: Int, val message: M) : Region<M>

    data class Viewed<M : Any>(val view: View) : Region<M>
}

/** An envelope as it crosses between regions: the id, then the message in the kind's own codec. */
private class EnvelopeCodec<M : Any>(private val codec: MessageCodec<M>) : MessageCodec<Region<M>> {
    override fun write(message: Region<M>, out: WireOut) = when (message) {
        is Region.Envelope -> {
            out.string(message.id)
            out.int(message.hops)
            codec.write(message.message, out)
        }

        is Region.Viewed -> error("a view never leaves its node")
    }

    override fun read(input: WireIn): Region<M> =
        Region.Envelope(id = input.string(), hops = input.int(), message = codec.read(input))
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
    val wire = EnvelopeCodec(codec)
    val placing = Placing(this, kind, shards, wire, path) { ctx ->
        ctx.spawn("entities", entities(passivateAfter, entity = entity))
    }
    val region = flock.spawn(
        path.removePrefix("/user/"),
        behaviour<Region<M>, Unit>(Unit) { ctx, _, step -> stay().also { placing.step(ctx, step) } },
    )
    remote.expose(region, wire)
    onView { region.tell(Region.Viewed(it)) }
    return Sharded(kind, region)
}

/** One region's work: each message to the entity here, or to the region of the node that owns its shard. */
private class Placing<M : Any>(
    private val cluster: Cluster,
    private val kind: String,
    private val shards: Int,
    private val wire: MessageCodec<Region<M>>,
    private val path: String,
    private val start: (Ctx<Region<M>>) -> ActorRef<Entities<M>>,
) {
    private var view = View.None
    private var local: ActorRef<Entities<M>>? = null
    private val kept = ArrayDeque<Region.Envelope<M>>()

    fun step(ctx: Ctx<Region<M>>, step: Region<M>) = when (step) {
        is Region.Envelope -> route(ctx, step)

        is Region.Viewed -> {
            view = step.view
            val waiting = kept.toList()
            kept.clear()
            waiting.forEach { route(ctx, it) }
        }
    }

    private fun route(ctx: Ctx<Region<M>>, envelope: Region.Envelope<M>) {
        val owner = Placement.owner(kind, Placement.shardOf(envelope.id, shards), view.members)
        when {
            owner == cluster.self -> here(ctx).entity(envelope.id).tell(envelope.message)
            owner == null || envelope.hops >= Sharding.MOST_HOPS -> keep(envelope)
            else -> there(owner).tell(envelope.copy(hops = envelope.hops + 1))
        }
    }

    private fun here(ctx: Ctx<Region<M>>) = local ?: start(ctx).also { local = it }

    private fun there(owner: Node) = cluster.remote.remote(Address(owner.toString(), path, 0), wire)

    private fun keep(envelope: Region.Envelope<M>) {
        if (kept.size < Sharding.KEEP_AT_MOST) return kept.addLast(envelope)
        val recipient = Address(cluster.self.toString(), "$path/${envelope.id}", 0)
        cluster.flock.deadLetter(DeadLetter(recipient, envelope.message, DeadLetter.Why.Unreachable))
    }
}
