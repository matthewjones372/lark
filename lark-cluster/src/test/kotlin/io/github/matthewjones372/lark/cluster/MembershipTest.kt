package io.github.matthewjones372.lark.cluster

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class MembershipTest {

    private val five = listOf(1, 2, 3, 4, 5)

    private fun Net.fiveUp() {
        five.forEach { start(it, seeds(1, 2, 3)) }
        withClue("five nodes agree on five Up") {
            until { five.all { view(it).up().size == 5 } && five.map(::view).distinct().size == 1 } shouldBe true
        }
    }

    @Test
    fun `five nodes started from the same seeds agree on one view, with all five Up and the lowest seed oldest`() {
        val net = Net().apply { fiveUp() }

        val view = net.view(3)
        view.members.map { it.node }.toSet() shouldBe five.map(::at).toSet()
        view.members.first().node shouldBe at(1)
        view.leader shouldBe at(1)
        view.unreachable.shouldBeEmpty()
    }

    @Test
    fun `a stopped node is seen unreachable by every other, and stays a member`() {
        val net = Net().apply { fiveUp() }

        net.stop(5)

        net.until { (1..4).all { at(5) in net.view(it).unreachable } } shouldBe true
        (1..4).forEach { net.view(it).up() shouldContainExactly net.view(1).up() }
        net.view(1).up().size shouldBe 5
    }

    @Test
    fun `a node one other cannot reach is still reachable, through the others probing it for that one`() {
        val net = Net().apply { fiveUp() }

        net.cut(1, 2)

        net.until(steps = 100) { five.any { net.view(it).unreachable.isNotEmpty() } } shouldBe false
    }

    @Test
    fun `a node cut off from all is unreachable, and reachable again once its links heal`() {
        val net = Net().apply { fiveUp() }

        net.isolate(4, listOf(1, 2, 3, 5))
        net.until { listOf(1, 2, 3, 5).all { at(4) in net.view(it).unreachable } } shouldBe true
        net.rejoin(4, listOf(1, 2, 3, 5))

        net.until { five.all { net.view(it).unreachable.isEmpty() } } shouldBe true
    }

    @Test
    fun `a joiner is not moved to Up while a member is unreachable, and is once it is back`() {
        val net = Net().apply { fiveUp() }
        net.isolate(5, listOf(1, 2, 3, 4))
        net.until { at(5) in net.view(1).unreachable } shouldBe true

        net.start(6, seeds(1))

        net.until(steps = 50) { at(6) in net.view(1).up() } shouldBe false
        net.view(1).members.first { it.node == at(6) }.status shouldBe Status.Joining
        net.rejoin(5, listOf(1, 2, 3, 4))
        net.until { (1..6).all { at(6) in net.view(it).up() } } shouldBe true
    }

    @Test
    fun `a node that restarts joins as a new member, and its earlier life is downed and removed`() {
        val net = Net(downing = Downing.keepMajority(2.seconds)).apply { fiveUp() }

        net.start(5, seeds(1), uid = 55)

        net.until { (1..5).all { v -> net.view(v).members.any { it.uid == 55L && it.status == Status.Up } } } shouldBe
            true
        net.until { net.view(1).members.none { it.node == at(5) && it.uid == 5L } } shouldBe true
    }

    @Test
    fun `a member that leaves is removed from every view`() {
        val net = Net().apply { fiveUp() }

        net.nodes.getValue(at(2)).leave()

        net.until { listOf(1, 3, 4, 5).all { v -> net.view(v).members.none { it.node == at(2) } } } shouldBe true
    }

    @Test
    fun `nodes of another cluster that probe this one are not taken in`() {
        val net = Net()
        (1..3).forEach { net.start(it, seeds(1)) }
        (7..8).forEach { net.start(it, seeds(7)) }
        net.until { net.view(3).up().size == 3 && net.view(8).up().size == 2 } shouldBe true

        // Node 8 learns of node 2, as a misconfigured seed list would have it, and asks to join through it.
        net.nodes.getValue(at(8)).receive(Swim.Join(Incarnation(at(2), 2)), net.now)
        net.until(steps = 30) { false }

        net.view(1).up() shouldContainExactly listOf(at(1), at(2), at(3))
        net.view(7).up() shouldContainExactly listOf(at(7), at(8))
    }

    @Test
    fun `a node that is not the lowest seed waits for one to form the cluster, and never forms one itself`() {
        val net = Net()
        net.start(2, seeds(1, 2))

        net.until(steps = 100) { net.view(2).members.isNotEmpty() } shouldBe false
        net.start(1, seeds(1, 2))
        net.until { net.view(2).up() == listOf(at(1), at(2)) } shouldBe true
    }

    @Test
    fun `an Up member that goes before its joiners hear them Up leaves no cluster without a leader`() {
        val net = Net(downing = Downing.keepMajority(2.seconds))
        net.start(1, seeds(1))
        net.until { net.view(1).up() == listOf(at(1)) } shouldBe true
        net.start(2, seeds(1))
        net.start(3, seeds(1))

        // Stopped in the step it moves them on, before any gossip saying so has left it.
        net.until { net.view(1).up().size == 3 } shouldBe true
        net.stop(1)
        withClue("the joiners still see only the member that went Up") { net.view(2).up() shouldBe listOf(at(1)) }

        net.until { listOf(2, 3).all { net.view(it).up().toSet() == setOf(at(2), at(3)) } } shouldBe true
        net.view(2).members.first { it.node == at(1) }.status shouldBe Status.Down
    }
}
