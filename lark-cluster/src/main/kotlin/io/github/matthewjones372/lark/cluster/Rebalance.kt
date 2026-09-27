package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.time.Duration

/** How a kind's shards follow the load (spec 0090): each member reports what it runs [every] so often. */
internal data class Rebalance(val every: Duration, val tolerance: Double, val mostMoves: Int)

/** The load of kinds that rebalance, as the gossip has it: per live member, kind and shard. */
internal data class Balance(val loads: Map<Node, Map<String, Map<Int, ShardLoad>>>) {
    companion object {
        val None = Balance(emptyMap())
    }
}

/** What one kind runs here, per shard: counted by its entities and its region, and read by the cluster actor. */
internal class LoadMeter(val kind: String, val rebalance: Rebalance, shards: Int) {
    private val running = AtomicIntegerArray(shards)
    private val handled = AtomicIntegerArray(shards)

    // Read and written only by the cluster actor.
    private var next = Duration.ZERO

    fun running(shard: Int, delta: Int) {
        running.addAndGet(shard, delta)
    }

    fun handled(shard: Int) {
        handled.incrementAndGet(shard)
    }

    /** The load of each shard with any, if an interval has passed since the last; messages count again from none. */
    fun due(now: Duration): Map<Int, ShardLoad>? {
        if (now < next) return null
        next = now + rebalance.every
        return (0 until running.length()).associateWith { ShardLoad(running[it], handled.getAndSet(it, 0)) }
            .filterValues { it.entities > 0 || it.messages > 0 }
    }
}
