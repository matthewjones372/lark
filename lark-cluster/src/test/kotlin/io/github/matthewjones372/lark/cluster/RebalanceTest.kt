package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val three = (1..3).map { Member(Node("m$it", "10.0.0.$it", 25520), it.toLong(), Status.Up, it) }

private fun Member.life() = Incarnation(node, uid)

/** The first [count] shards of "k" that [member] owns by hash. */
private fun ownedBy(member: Member, count: Int) =
    (0 until Sharding.SHARDS).filter { Placement.owner("k", it, three) == member.node }.take(count)

/** Each member's load of "k", every shard counted on the member that owns it now. */
private fun totals(load: Map<Int, Int>, moved: Map<Int, Incarnation>) = three.associate { m ->
    m.node to load.filterKeys { Placement.owner("k", it, three, moved[it]) == m.node }.values.sum()
}

/** A balance in which every shard's owner reports [load] of entities for it. */
private fun balance(load: Map<Int, Int>, moved: Map<Int, Incarnation>) = Balance(
    three.associate { m ->
        val mine = load.filterKeys { Placement.owner("k", it, three, moved[it]) == m.node }
        m.node to mapOf("k" to mine.mapValues { ShardLoad(it.value, 0) })
    },
    mapOf("k" to moved),
)

/** Applies [moves] as the leader's gossip would: a null move sends the shard back to its hash owner. */
private fun Map<Int, Incarnation>.after(moves: Map<Int, Node?>) = moves.entries.fold(this) { moved, (shard, to) ->
    if (to == null) moved - shard else moved + (shard to three.first { it.node == to }.life())
}

class RebalanceTest {
    private val rebalance = Rebalance.byLoad(every = 1.minutes, tolerance = 0.2, mostMoves = 4)
    private val led = View(three, emptySet(), three[0].node)
    private val meter = LoadMeter("k", rebalance, Sharding.SHARDS, role = null)

    private fun Balancer.round(at: Duration, balance: Balance) =
        moves(at, listOf(meter), led, three[0].node, balance)["k"].orEmpty()

    @Test
    fun `the busiest member falls within tolerance of the mean within three intervals`() {
        val load = ownedBy(three[0], 12).associateWith { 10 }
        val balancer = Balancer()
        var moved = emptyMap<Int, Incarnation>()
        balancer.round(Duration.ZERO, balance(load, moved)).shouldBeEmpty()

        val rounds = (1..3).map { round ->
            balancer.round(rebalance.every * round, balance(load, moved)).also { moved = moved.after(it) }
        }

        withClue("no round moves more than mostMoves") { rounds.forEach { it.size shouldBeLessThanOrEqual 4 } }
        val totals = totals(load, moved)
        totals.values.max().toDouble() shouldBeLessThanOrEqual totals.values.sum() / 3.0 * 1.2
    }

    @Test
    fun `a moved shard is not moved again within three intervals, even where it would be the one to move`() {
        val (a, b, c) = ownedBy(three[0], 3)
        val e = ownedBy(three[1], 1).single()
        val g = ownedBy(three[2], 1).single()
        val balancer = Balancer()
        balancer.round(Duration.ZERO, Balance.None).shouldBeEmpty()

        val first = balancer.round(rebalance.every, balance(mapOf(a to 10, b to 10, c to 10, g to 10), emptyMap()))
        first shouldBe mapOf(a to three[1].node)
        // The load grew where the shard went, so that it is now the busiest member's heaviest.
        val grown = mapOf(a to 50, b to 10, c to 10, e to 30, g to 10)
        var moved = emptyMap<Int, Incarnation>().after(first)

        val second = balancer.round(rebalance.every * 2, balance(grown, moved))
        second shouldBe mapOf(e to three[2].node)
        moved = moved.after(second)
        balancer.round(rebalance.every * 3, balance(grown, moved)).shouldBeEmpty()
    }

    @Test
    fun `a shard just moved counts at the load it moved with until its new owner reports it`() {
        val (a, b, c, d) = ownedBy(three[0], 4)
        val balancer = Balancer()
        balancer.round(Duration.ZERO, Balance.None)
        val first = balancer.round(rebalance.every, balance(listOf(a, b, c, d).associateWith { 10 }, emptyMap()))
        first shouldBe mapOf(a to three[1].node, b to three[2].node)

        val moved = emptyMap<Int, Incarnation>().after(first)
        balancer.round(rebalance.every * 2, balance(mapOf(c to 10, d to 10), moved)).shouldBeEmpty()
    }
}
