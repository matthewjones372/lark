package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun up(n: Int, status: Status = Status.Up) = Member(Node("n$n", "10.0.0.$n", 25520), n.toLong(), status, n)

private fun owners(members: List<Member>, shards: Int = 256) =
    List(shards) { Placement.owner("order", it, members) }

class PlacementTest {

    @Test
    fun `an id is always in the same shard, and every shard is one of the kind's`() {
        val shards = (1..10_000).map { Placement.shardOf("order-$it", 256) }

        shards.forEach { it shouldBeInRange 0..255 }
        (1..10_000).map { Placement.shardOf("order-$it", 256) } shouldBe shards
        withClue("every shard has some id") { shards.toSet().size shouldBe 256 }
    }

    @Test
    fun `every node that has the same members agrees on every owner, whatever order it has them in`() {
        val five = (1..5).map { up(it) }

        owners(five.shuffled()) shouldBe owners(five)
        owners(five.reversed()) shouldBe owners(five)
    }

    @Test
    fun `the shards are spread across the members`() {
        val counts = owners((1..5).map { up(it) }).groupingBy { it }.eachCount()

        counts.keys.size shouldBe 5
        counts.values.forEach { it shouldBeInRange 30..80 }
    }

    @Test
    fun `a member joining takes about its share of shards, and no shard moves between the others`() {
        val before = owners((1..5).map { up(it) })
        val after = owners((1..6).map { up(it) })

        val moved = before.indices.filter { before[it] != after[it] }
        moved.map { after[it] }.toSet() shouldBe setOf(up(6).node)
        moved.size shouldBeInRange 20..70
    }

    @Test
    fun `a member leaving gives up only its own shards`() {
        val before = owners((1..5).map { up(it) })
        val after = owners((1..4).map { up(it) })

        before.indices.filter { before[it] != after[it] }.filterNot { before[it] == up(5).node }.shouldBeEmpty()
    }

    @Test
    fun `only Up members own shards, and with none up no shard has an owner`() {
        val members = listOf(up(1), up(2, Status.Joining), up(3, Status.Leaving), up(4, Status.Down))

        owners(members).toSet() shouldBe setOf(up(1).node)
        Placement.owner("order", 0, listOf(up(2, Status.Joining))).shouldBeNull()
    }
}
