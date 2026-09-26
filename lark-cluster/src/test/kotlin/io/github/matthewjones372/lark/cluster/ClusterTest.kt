package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** A node on a flock and a thread of its own, until [close]. */
private class Running(name: String, port: Int, seeds: Discovery) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val joined = AtomicReference<Cluster>()
    private val thread = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            joined.set(cluster(node(name, port), seeds, quick))
            ready.countDown()
            done.await()
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

private fun View.up() = members.filter { it.status == Status.Up }.map { it.node.name }.toSet()

class ClusterTest {

    @Test
    fun `five nodes in one JVM agree on the same view, and one that stops is seen unreachable by all the rest`() {
        val ports = List(5) { freePort() }
        val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
        val names = (1..5).map { "n$it" }
        val nodes = names.zip(ports).map { (name, port) -> Running(name, port, seeds) }
        try {
            val views = nodes.map { node ->
                withClue("${node.cluster.self} sees all five Up") {
                    node.cluster.await(1.minutes) { it.up() == names.toSet() } shouldBe true
                }
                node.cluster.view.members
            }
            views.distinct().size shouldBe 1
            // The lowest seed forms the cluster, so it is the oldest member.
            val oldest = names[ports.indexOf(ports.take(3).min())]
            nodes.map { it.cluster.view.leader?.name }.distinct() shouldBe listOf(oldest)

            nodes.last().close()

            nodes.dropLast(1).forEach { node ->
                withClue("${node.cluster.self} sees n5 unreachable") {
                    node.cluster.await(1.minutes) { view -> view.unreachable.any { it.name == "n5" } } shouldBe true
                }
            }
        } finally {
            nodes.forEach(Running::close)
        }
    }
}
