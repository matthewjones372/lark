package io.github.matthewjones372.lark.app.actor

import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.lark.cluster.Discovery
import io.github.matthewjones372.lark.cluster.Gossiping
import io.github.matthewjones372.lark.cluster.MemberEvent
import io.github.matthewjones372.lark.cluster.Status
import io.github.matthewjones372.lark.cluster.cluster
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

class LeaveOnReleaseTest {

    @Test
    fun `a cluster made in the actors' flock leaves it as the application is released`() {
        val ports = List(2) { freePort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val heard = ConcurrentLinkedQueue<MemberEvent>()
        val bothUp = CountDownLatch(2)
        val removed = CountDownLatch(1)
        val stays = Thread.ofPlatform().start {
            flock<Nothing, Unit> {
                val cluster = cluster(node("stays", ports[0]), seeds, quick)
                val listener = spawn(
                    "listener",
                    behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
                        heard += event
                        if (event is MemberEvent.Up) bothUp.countDown()
                        if (event is MemberEvent.Removed && event.member.node.name == "app") removed.countDown()
                        stay()
                    },
                )
                cluster.subscribe(listener)
                removed.await(1, TimeUnit.MINUTES)
            }
        }

        val module =
            actors() + single { actors: Actors -> actors.within { cluster(node("app", ports[1]), seeds, quick) } }
        var released = TimeSource.Monotonic.markNow()
        testApp(module) { _: Cluster ->
            bothUp.await(1, TimeUnit.MINUTES) shouldBe true
            released = TimeSource.Monotonic.markNow()
        }

        // Out before the 20 s a node left alone waits to down itself. How it left is the Leaving status asserted below,
        // which a self-down would not carry; this bound only catches a leave that hangs.
        released.elapsedNow() shouldBeLessThan 20.seconds

        removed.await(1, TimeUnit.MINUTES) shouldBe true
        stays.join()
        val app = heard.filter { it.member.node.name == "app" }
        app.map { it::class.simpleName } shouldBe listOf("Up", "Removed")
        app.last().member.status shouldBe Status.Leaving
    }
}
