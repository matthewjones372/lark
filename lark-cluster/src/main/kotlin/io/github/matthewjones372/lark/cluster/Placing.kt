package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Ctx
import io.github.matthewjones372.lark.actor.DeadLetter
import io.github.matthewjones372.lark.actor.deadLetter
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node

/**
 * What a region runs its shards with: which member owns a shard, what runs one here, and how a message reaches what
 * runs it. [eager] starts a shard's host as soon as the shard is free here, rather than on its first message.
 */
internal class Hosting<M : Any, H : Any>(
    val eager: Boolean,
    val owner: (shard: Int, members: List<Member>) -> Node?,
    val start: (Ctx<Region<M>>, shard: Int) -> ActorRef<H>,
    val deliver: (host: ActorRef<H>, id: String, message: M) -> Unit,
)

/** What a region gauges (spec 0081): the shards it may run now, and what it keeps for a shard with no owner yet. */
internal class RegionMeters(val shards: Gauge, val buffered: Gauge)

/** The key of a region's retry timer: one at a time, however many messages ran out of hops. */
private object RetryKey

/**
 * One region's work: each message to the entity here, or to the region of the node that owns its shard; and the
 * handoff that keeps an entity from running on two nodes at once.
 *
 * A node that wins a shard asks every other member that could hold it, `Up` or `Leaving`, to release it, and runs
 * none of the shard until each has, or has left the view. A member releases a shard only once it neither runs any of
 * it nor believes it owns it, so two nodes whose views disagree both wait until the views agree. What arrives for a
 * shard meanwhile is kept, up to a bound.
 */
internal class Placing<M : Any, H : Any>(
    private val cluster: Cluster,
    private val shards: Int,
    private val wire: MessageCodec<Region<M>>,
    private val path: String,
    private val hosting: Hosting<M, H>,
    private val meters: RegionMeters,
) {
    private val self = cluster.self
    private var view = View.None

    // Shards this node may run now; the managers of those it has run since; and those it has let go, until they end.
    private val ready = HashSet<Int>()
    private val hosted = HashMap<Int, ActorRef<H>>()
    private val stopping = HashMap<ActorRef<*>, Int>()

    // Shards won, and who has yet to release each; and who has asked for a shard this node still holds.
    private val awaiting = HashMap<Int, MutableSet<Node>>()
    private val owed = HashMap<Int, MutableSet<Node>>()

    private val kept = ArrayDeque<Region.Envelope<M>>()

    fun step(ctx: Ctx<Region<M>>, step: Region<M>) {
        handle(ctx, step)
        measure()
    }

    private fun measure() {
        meters.shards.set(ready.size.toDouble())
        meters.buffered.set(kept.size.toDouble())
    }

    private fun handle(ctx: Ctx<Region<M>>, step: Region<M>) = when (step) {
        is Region.Envelope -> route(ctx, step)

        is Region.Viewed -> viewed(ctx, step.view)

        is Region.Release -> asked(step.shard, by = step.from)

        is Region.Released -> {
            awaiting[step.shard]?.remove(step.by)
            startIfFree(ctx, step.shard)
        }

        is Region.Retry -> retry(ctx)
    }

    /** A shard's manager has stopped, and every entity of the shard with it. */
    fun ended(ctx: Ctx<Region<M>>, ref: ActorRef<*>) {
        // What stopped of its own accord, rather than let go, starts again on the shard's next message.
        hosted.entries.removeIf { it.value == ref }
        val shard = stopping.remove(ref) ?: return
        awaiting[shard]?.remove(self)
        startIfFree(ctx, shard)
        settle()
        measure()
    }

    private fun owner(shard: Int) = hosting.owner(shard, view.members)

    private fun mine(shard: Int) = owner(shard) == self

    private fun route(ctx: Ctx<Region<M>>, envelope: Region.Envelope<M>) {
        val shard = Placement.shardOf(envelope.id, shards)
        val owner = owner(shard)
        when {
            owner == self && shard in ready -> hosting.deliver(host(ctx, shard), envelope.id, envelope.message)

            owner == self || owner == null -> keep(envelope)

            envelope.hops >= Sharding.MOST_HOPS -> {
                // Passed between nodes whose views disagree: this one may already have the view that settles it.
                keep(envelope)
                ctx.timers.after(RetryKey, Sharding.RETRY_AFTER, Region.Retry())
            }

            else -> there(owner).tell(envelope.copy(hops = envelope.hops + 1))
        }
    }

    private fun viewed(ctx: Ctx<Region<M>>, next: View) {
        view = next
        ready.filterNot(::mine).forEach { letGo(ctx, it) }
        awaiting.keys.removeAll { !mine(it) }
        val holders = next.members.filter { it.status == Status.Up || it.status == Status.Leaving }
            .map { it.node }.filterTo(mutableSetOf()) { it != self }
        for (shard in 0 until shards) {
            if (mine(shard) && shard !in ready && shard !in awaiting) win(shard, holders)
        }
        awaiting.values.forEach { left -> left.retainAll(holders + self) }
        awaiting.keys.toList().forEach { startIfFree(ctx, it) }
        settle()
        retry(ctx)
    }

    private fun win(shard: Int, holders: Set<Node>) {
        awaiting[shard] = holders.toMutableSet().apply { if (shard in stopping.values) add(self) }
        holders.forEach { there(it).tell(Region.Release(shard, self)) }
    }

    private fun startIfFree(ctx: Ctx<Region<M>>, shard: Int) {
        if (awaiting[shard]?.isEmpty() != true || !mine(shard)) return
        awaiting -= shard
        ready += shard
        if (hosting.eager) host(ctx, shard)
        retry(ctx)
    }

    private fun letGo(ctx: Ctx<Region<M>>, shard: Int) {
        ready -= shard
        hosted.remove(shard)?.let { manager ->
            stopping[manager] = shard
            ctx.stop(manager)
        }
    }

    private fun asked(shard: Int, by: Node) {
        owed.getOrPut(shard) { mutableSetOf() } += by
        settle()
    }

    /** Answers everyone who asked for a shard this node neither owns, runs, nor is still stopping. */
    private fun settle() {
        owed.entries.removeIf { (shard, askers) ->
            val holding = mine(shard) || shard in ready || shard in stopping.values
            if (!holding) askers.forEach { there(it).tell(Region.Released(shard, self)) }
            !holding
        }
    }

    private fun host(ctx: Ctx<Region<M>>, shard: Int) =
        hosted.getOrPut(shard) { hosting.start(ctx, shard).also(ctx::watch) }

    private fun there(node: Node) = cluster.remote.remote(Address(node.toString(), path, 0), wire)

    /** Routes again what was kept, each with its hops back to none: the view it was kept under has changed. */
    private fun retry(ctx: Ctx<Region<M>>) {
        val waiting = kept.toList()
        kept.clear()
        waiting.forEach { route(ctx, it.copy(hops = 0)) }
    }

    private fun keep(envelope: Region.Envelope<M>) {
        if (kept.size < Sharding.KEEP_AT_MOST) return kept.addLast(envelope)
        val recipient = Address(self.toString(), "$path/${envelope.id}", 0)
        cluster.flock.deadLetter(DeadLetter(recipient, envelope.message, DeadLetter.Why.Unreachable))
    }
}
