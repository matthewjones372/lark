package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.RemoteNode
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.watch
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** A node on a flock and a thread of its own, until [close]. */
private class Running(
    name: String,
    port: Int,
    seeds: Discovery,
    setup: Flock<Nothing>.(RemoteNode) -> Unit = {},
) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val joined = AtomicReference<Cluster>()
    private val thread = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            val node = node(name, port)
            setup(node)
            joined.set(cluster(node, seeds, quick))
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

private fun View.upNames() = members.filter { it.status == Status.Up }.map { it.node.name }.toSet()

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
                    node.cluster.await(1.minutes) { it.upNames() == names.toSet() } shouldBe true
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

    @Test
    fun `a subscriber hears each member come Up, and a member that leaves is removed and ends every watch on it`() {
        val ports = List(3) { freePort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val second = Running("n2", ports[1], seeds)
        val third = Running("n3", ports[2], seeds) { node ->
            node.expose(spawn("ward", behaviour<String, Unit>(Unit) { _, _, _ -> stay() }), Codecs.string)
        }
        try {
            flock<Nothing, Unit> {
                val heard = LinkedBlockingQueue<MemberEvent>()
                val node = node("n1", ports[0])
                val cluster = cluster(node, seeds, quick)
                val listener = behaviour<MemberEvent, Unit>(Unit) { _, _, event -> stay().also { heard.put(event) } }
                cluster.subscribe(spawn("listener", listener))
                fun <E : MemberEvent> next(type: Class<E>): E =
                    generateSequence { heard.poll(1, TimeUnit.MINUTES) }.filterIsInstance(type).first()
                List(3) { next(MemberEvent.Up::class.java).member.node.name }.toSet() shouldBe setOf("n1", "n2", "n3")

                val ward = node.remote(Address("n3@127.0.0.1:${ports[2]}", "/user/ward", 0), Codecs.string)
                val watched = watch(ward)
                third.cluster.leave()

                next(MemberEvent.Removed::class.java).member.node.name shouldBe "n3"
                watched.await().ref shouldBe ward
            }
        } finally {
            second.close()
            third.close()
        }
    }
}
