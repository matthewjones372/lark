package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.Histogram
import io.github.matthewjones372.lark.Metrics
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.metrics
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.DoubleAdder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One node's metrics, kept by name and tags. */
private class NodeMetrics : Metrics {
    private val counters = ConcurrentHashMap<Pair<String, Map<String, String>>, DoubleAdder>()
    private val gauges = ConcurrentHashMap<Pair<String, Map<String, String>>, Double>()

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val adder = counters.computeIfAbsent(name to tags) { DoubleAdder() }
        return Counter { adder.add(it) }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { gauges[name to tags] = it }

    override fun histogram(name: String, tags: Map<String, String>): Histogram = Histogram { }

    fun counter(name: String, vararg tags: Pair<String, String>): Double = counters[name to tags.toMap()]?.sum() ?: 0.0

    fun gauge(name: String, vararg tags: Pair<String, String>): Double? = gauges[name to tags.toMap()]
}

private val brisk =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun anyPort(): Int = ServerSocket(0).use { it.localPort }

/** A node on a thread of its own, measuring into [measured], that goes as a crashed node does when closed. */
private class MeasuredNode(val name: String, port: Int, seeds: Discovery) : AutoCloseable {
    val measured = NodeMetrics()
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val joined = AtomicReference<Cluster>()
    private val thread = Thread.ofPlatform().start {
        metrics.locally(measured) {
            flock<Nothing, Unit> {
                val downing = Downing.keepMajority(2.seconds)
                joined.set(cluster(node(name, port), seeds, brisk, downing, leaveWithin = Duration.ZERO))
                ready.countDown()
                done.await()
            }
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return joined.get()
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

class ClusterMetricsTest {

    @Test
    fun `members are gauged with one leader, and a crash is unreachable and not ready until it is downed`() {
        val ports = List(3) { anyPort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val nodes = ports.mapIndexed { i, port -> MeasuredNode("m${i + 1}", port, seeds) }
        try {
            nodes.forEach { n ->
                n.cluster.await(1.minutes) { v ->
                    v.members.count { it.status == Status.Up } == 3 && v.leader != null && v.unreachable.isEmpty()
                } shouldBe true
            }

            nodes.forEach { n ->
                n.measured.gauge("lark.cluster.members", "node" to n.name, "status" to "Up") shouldBe 3.0
            }
            nodes.sumOf { n -> n.measured.gauge("lark.cluster.leader", "node" to n.name)!! } shouldBe 1.0
            nodes.forEach { n -> n.cluster.ready() shouldBe true }

            val (first, second, crashed) = nodes
            crashed.close()
            listOf(first, second).forEach { n ->
                n.cluster.await(1.minutes) { v -> v.unreachable.any { it.name == crashed.name } } shouldBe true
                n.cluster.ready() shouldBe false
            }
            listOf(first, second).forEach { n ->
                n.cluster.await(1.minutes) { v -> v.members.none { it.node.name == crashed.name } } shouldBe true
                n.cluster.ready() shouldBe true
                n.measured.gauge("lark.cluster.unreachable", "node" to n.name) shouldBe 0.0
                n.measured.counter("lark.cluster.downed", "node" to n.name) shouldBe 1.0
            }
        } finally {
            nodes.forEach(MeasuredNode::close)
        }
    }
}
