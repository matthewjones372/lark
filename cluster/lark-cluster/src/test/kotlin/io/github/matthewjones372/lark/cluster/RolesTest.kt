package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val nimble =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun unusedPort(): Int = ServerSocket(0).use { it.localPort }

/** A node started with [roles], on a thread of its own, until [close], which leaves the cluster. */
private class Worker(val name: String, port: Int, seeds: Discovery, roles: Set<String>) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val joined = AtomicReference<Cluster>()
    private val thread = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            joined.set(cluster(node(name, port), seeds, nimble, roles = roles))
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

private fun View.roles(): Map<String, Set<String>> =
    members.filter { it.status == Status.Up }.associate { it.node.name to it.roles }

class RolesTest {

    @Test
    fun `every member sees every member's roles, and a node started again with other roles shows the new ones`() {
        val ports = List(3) { unusedPort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val roles = listOf(setOf("ledger"), setOf("web"), setOf("ledger", "web"))
        val first = mapOf("r1" to roles[0], "r2" to roles[1], "r3" to roles[2])
        val workers = ports.indices.map { Worker("r${it + 1}", ports[it], seeds, roles[it]) }.toMutableList()
        try {
            workers.forEach { it.cluster.await(1.minutes) { view -> view.roles() == first } shouldBe true }

            workers.removeLast().close()
            workers += Worker("r3", ports[2], seeds, setOf("batch"))

            val again = first + ("r3" to setOf("batch"))
            workers.forEach { it.cluster.await(1.minutes) { view -> view.roles() == again } shouldBe true }
        } finally {
            workers.forEach(Worker::close)
        }
    }
}
