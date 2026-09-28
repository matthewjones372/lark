package io.github.matthewjones372.lark.app.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.actor.actors
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.lark.cluster.Discovery
import io.github.matthewjones372.lark.cluster.Downing
import io.github.matthewjones372.lark.cluster.Gossiping
import io.github.matthewjones372.lark.cluster.Joining
import io.github.matthewjones372.lark.cluster.Status
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

/** Spec 0096: a cluster as a node, joined as a HOCON section says. */
class ClusteredTest {

    private fun hocon(name: String, port: Int, seeds: List<Int>, join: String = "static") = """
        lark.cluster {
          node { name = $name, port = $port }
          join = $join
          static.seeds = [${seeds.joinToString { "\"127.0.0.1:$it\"" }}]
          gossip { probeEvery = 200ms, ackWithin = 60ms, formAfter = 1s }
          whenDowned = stay
        }
    """.trimIndent()

    @Test
    fun `three nodes join from config alone, each ready once all three are up`() {
        val ports = List(3) { freePort() }
        val allUp = CountDownLatch(3)
        val done = CountDownLatch(1)
        val names = listOf("a", "b", "c")
        val nodes = names.zip(ports).map { (name, port) ->
            Thread.ofPlatform().start {
                testApp(configOf(hocon(name, port, ports)) + actors() + cluster("lark.cluster")) { cluster: Cluster ->
                    cluster.ready() shouldBe true
                    while (cluster.view.members.count { it.status == Status.Up } < 3) Thread.sleep(50)
                    allUp.countDown()
                    done.await(1, TimeUnit.MINUTES)
                }
            }
        }
        allUp.await(1, TimeUnit.MINUTES) shouldBe true
        done.countDown()
        nodes.forEach(Thread::join)
    }

    @Test
    fun `a backend not on the classpath refuses the start, naming the module to add`() {
        val module =
            configOf(hocon("a", freePort(), emptyList(), join = "kubernetes")) + actors() + cluster("lark.cluster")
        val refused = module.use { _: Cluster -> }.leftOrNull().shouldBeInstanceOf<StartupError.Refused>()
        refused.reason shouldContain "join = kubernetes needs lark-cluster-kubernetes on the classpath"
    }

    @Test
    fun `a section missing what a node needs refuses the start, naming every fault`() {
        val module = configOf("lark.cluster { join = static }") + actors() + cluster("lark.cluster")
        val refused = module.use { _: Cluster -> }.leftOrNull().shouldBeInstanceOf<StartupError.Refused>()
        // Typesafe Config's own words, one fault for the name and one for the port, both said at once.
        refused.reason shouldContain "No configuration setting found for key 'node'"
        refused.reason.split("; ").size shouldBe 2
    }

    @Test
    fun `the joining is closed only after the node has left its cluster`() {
        val ports = List(2) { freePort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        var joined: Cluster? = null
        var closed = false
        var statusAtClose: Status? = null
        val stays = CountDownLatch(1)
        val other = Thread.ofPlatform().start {
            testApp(actors() + cluster(settings("stays", ports[0], seeds) {})) { _: Cluster ->
                stays.await(1, TimeUnit.MINUTES)
            }
        }
        val leaving = settings("leaves", ports[1], seeds) {
            val cluster = joined.shouldNotBeNull()
            statusAtClose = cluster.view.members.firstOrNull { it.node == cluster.self }?.status
            closed = true
        }
        testApp(actors() + cluster(leaving)) { cluster: Cluster ->
            joined = cluster
            while (cluster.view.members.count { it.status == Status.Up } < 2) Thread.sleep(50)
        }
        stays.countDown()
        other.join()
        closed shouldBe true
        // Leaving or already out: anything but still Up, which a joining closed before the leave would see.
        statusAtClose shouldNotBe Status.Up
    }

    private fun settings(name: String, port: Int, seeds: Discovery, released: () -> Unit) = ClusterSettings(
        name = name,
        host = "127.0.0.1",
        port = port,
        joining = { Joining(seeds, Downing.keepMajority(), released) },
        gossiping = quick,
        whenDowned = WhenDowned.Stay,
    )

    @Test
    fun `a node restarted at its address is not ended by its earlier life's downing`() {
        val ports = List(3) { freePort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val done = CountDownLatch(1)

        // Each goes as a crashed node does, so the others still hold n3's earlier life when it comes back.
        fun crashing(name: String, port: Int, exit: () -> Unit = {}) = actors() +
            single<ClusterSettings> {
                ClusterSettings(name, "127.0.0.1", port, { Joining(seeds, Downing.keepMajority()) }, quick, 0.seconds)
            } + clustered(exit)
        val others = listOf("n1", "n2").zip(ports).map { (name, port) ->
            Thread.ofPlatform().start {
                testApp(crashing(name, port)) { _: Cluster -> done.await(2, TimeUnit.MINUTES) }
            }
        }
        testApp(crashing("n3", ports[2])) { cluster: Cluster ->
            while (cluster.view.members.count { it.status == Status.Up } < 3) Thread.sleep(50)
        }

        var exits = 0
        testApp(crashing("n3", ports[2]) { exits++ }) { cluster: Cluster ->
            // Up, and told of its earlier life's downing, which names this address.
            fun earlierDowned() =
                cluster.view.members.any { it.node == cluster.self && !cluster.isSelf(it) && it.status == Status.Down }
            while (!earlierDowned()) Thread.sleep(50)
            Thread.sleep(500)
        }
        done.countDown()
        others.forEach(Thread::join)
        exits shouldBe 0
    }
}
