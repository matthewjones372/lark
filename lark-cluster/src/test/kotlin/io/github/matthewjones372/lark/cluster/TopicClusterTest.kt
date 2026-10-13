package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.Topic
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val swift =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun portFree(): Int = ServerSocket(0).use { it.localPort }

/** A node with the topic "prices" on a thread of its own, and a subscriber whose hearing it keeps. */
private class Exchange(val name: String, port: Int, seeds: Discovery) : AutoCloseable {
    val heard = LinkedBlockingQueue<Int>()
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Topic<Int>>>()
    private val thread = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            val cluster = cluster(node(name, port), seeds, swift)
            val prices = cluster.topic("prices", Codecs.int)
            prices.subscribe(spawn("listener", behaviour<Int, Unit>(Unit) { _, _, n -> stay().also { heard += n } }))
            opened.set(cluster to prices)
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get().first
        }

    val prices: Topic<Int>
        get() {
            ready.await()
            return opened.get().second
        }

    /** The next [n] numbers heard, each within a minute. */
    fun next(n: Int): List<Int> = List(n) { heard.poll(1, TimeUnit.MINUTES) ?: error("$name heard nothing more") }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

class TopicClusterTest {

    @Test
    fun `a publish on one node reaches every subscriber on every member once, in order, a later member included`() {
        val ports = List(4) { portFree() }
        val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
        val nodes = mutableListOf(
            Exchange("x1", ports[0], seeds),
            Exchange("x2", ports[1], seeds),
            Exchange("x3", ports[2], seeds),
        )
        try {
            val up = { count: Int -> { view: View -> view.members.count { it.status == Status.Up } == count } }
            nodes.forEach { it.cluster.await(1.minutes, up(3)) shouldBe true }
            val publisher = nodes[2]

            (1..100).forEach(publisher.prices::publish)

            // The publisher's own subscriber among them: it hears each publish once, not again from the others.
            nodes.forEach { it.next(100) shouldBe (1..100).toList() }

            nodes += Exchange("x4", ports[3], seeds)
            nodes.forEach { it.cluster.await(1.minutes, up(4)) shouldBe true }
            (101..110).forEach(publisher.prices::publish)

            nodes.forEach { it.next(10) shouldBe (101..110).toList() }

            // Each hears the next publish next: in order, anything heard twice would have come before it.
            publisher.prices.publish(111)
            nodes.forEach { it.next(1) shouldBe listOf(111) }
        } finally {
            nodes.forEach(Exchange::close)
        }
    }
}
