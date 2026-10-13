package io.github.matthewjones372.lark.cluster

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class OverrideTest {

    @Test
    fun `an override set on the leader moves exactly its shard through the handoff, and goes when its member does`() {
        val ports = List(3) { loadPort() }
        val seeds = seedsAt(ports)
        val rarely = Rebalance.byLoad(every = 1.minutes)
        val nodes = ports.indices.map { LoadNode("v${it + 1}", ports[it], seeds, rarely, 1.minutes) }.toMutableList()
        try {
            nodes.forEach { n ->
                n.cluster.await(1.minutes) { v -> v.members.count { it.status == Status.Up } == 3 } shouldBe true
            }
            val lead = nodes.first().cluster.view.leader
            nodes.forEach { n -> n.cluster.await(20.seconds) { v -> v.leader == lead } shouldBe true }
            val leader = nodes.first { it.cluster.self == lead }
            val target = nodes.first { it != leader }

            val ids = (0 until 200).map { "o-$it" }
            val before = ids.withIndex().associate { (i, id) -> id to nodes[i % 3].tally(id) }
            val (shard, moving) = ids.groupBy { Placement.shardOf(it, Sharding.SHARDS) }.entries
                .filter { (_, of) -> before.getValue(of.first()) != target.name }.maxBy { it.value.size }
            leader.cluster.move("tally", shard, target.cluster.self)

            val during = (1..10).flatMap { round -> moving.map { nodes[round % 3].tally(it) } }
            withClue("asked during the move, only the old owner or the new answers") {
                (during.toSet() - setOf(before.getValue(moving.first()), target.name)).shouldBeEmpty()
            }
            nodes.forEach { n ->
                n.cluster.await(20.seconds) { _ -> n.moved()[shard]?.node == target.cluster.self } shouldBe true
            }
            val after = ids.withIndex().associate { (i, id) -> id to nodes[(i + 1) % 3].tally(id) }
            after shouldBe before + moving.associateWith { target.name }
            withClue("no entity ran on two nodes at once") { ranTwice.filter { it.startsWith("o-") }.shouldBeEmpty() }

            target.close()
            nodes -= target
            nodes.forEach { n ->
                val gone = n.cluster.await(30.seconds) { v ->
                    v.members.none { it.node == target.cluster.self } && n.moved().isEmpty()
                }
                withClue({ "${n.name} still has ${n.cluster.balance.moved}" }) { gone shouldBe true }
            }
            val owner = Placement.owner("tally", shard, nodes.first().cluster.view.members)?.name
            moving.map { nodes.first().tally(it) }.toSet() shouldBe setOf(owner)
        } finally {
            nodes.forEach(LoadNode::close)
        }
    }
}
