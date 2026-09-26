package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val settings = Gossiping(probeEvery = 1.seconds, ackWithin = 300.milliseconds, formAfter = 5.seconds)

private fun at(n: Int) = Node("n$n", "10.0.0.$n", 25520)

/** Seeds as DNS gives them: host and port, no name. */
private fun seeds(vararg n: Int) = n.map { Node("", at(it).host, at(it).port) }

/**
 * Nodes on a network that is a test step: every message sent in a step arrives within it, unless its link is cut or
 * a side has stopped. Time moves only when the test says.
 */
private class Net(private val seed: Int = 1) {
    val nodes = mutableMapOf<Node, Membership>()
    private val cut = mutableSetOf<Set<Node>>()
    private val stopped = mutableSetOf<Node>()
    var now: Duration = Duration.ZERO
        private set

    fun start(n: Int, seeds: List<Node>, uid: Long = n.toLong()): Membership =
        Membership(Incarnation(at(n), uid), { seeds }, settings, Random(seed * 31 + n + uid.toInt()), now)
            .also { nodes[at(n)] = it }

    fun stop(n: Int) {
        stopped += at(n)
    }

    fun cut(a: Int, b: Int) {
        cut += setOf(at(a), at(b))
    }

    fun heal(a: Int, b: Int) {
        cut -= setOf(at(a), at(b))
    }

    fun isolate(n: Int, from: List<Int>) = from.forEach { cut(n, it) }

    fun rejoin(n: Int, from: List<Int>) = from.forEach { heal(n, it) }

    /** Runs up to [steps] steps of [every], stopping at the first after which [done] holds; whether it did. */
    fun until(steps: Int = 200, every: Duration = 100.milliseconds, done: () -> Boolean): Boolean {
        repeat(steps) {
            now += every
            val flying = ArrayDeque<Pair<Node, Send>>()
            live().forEach { (node, m) -> m.tick(now).forEach { flying += node to it } }
            while (flying.isNotEmpty()) {
                val (from, send) = flying.removeFirst()
                val to = live().keys.firstOrNull { node -> reaches(send.to, node) && setOf(from, node) !in cut }
                to?.let { there -> nodes.getValue(there).receive(send.message, now).forEach { flying += there to it } }
            }
            if (done()) return true
        }
        return false
    }

    private fun live() = nodes.filterKeys { it !in stopped }

    private fun reaches(address: Node, node: Node) =
        address.host == node.host && address.port == node.port && (address.name.isEmpty() || address.name == node.name)

    fun view(n: Int): View = nodes.getValue(at(n)).view()
}

private fun View.up() = members.filter { it.status == Status.Up }.map { it.node }

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
        val net = Net().apply { fiveUp() }

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
}
