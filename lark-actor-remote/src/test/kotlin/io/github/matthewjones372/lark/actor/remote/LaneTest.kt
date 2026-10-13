package io.github.matthewjones372.lark.actor.remote

import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Spec 0104: the cluster's own traffic on a lane of its own, never behind, or dropped with, ordinary messages. */
class LaneTest {

    @Test
    fun `a control frame arrives at once, and none is dropped, while the data lane to its peer is jammed`() {
        val port = freePort()
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pinged = LinkedBlockingQueue<Int>()
        val receiver = Thread.ofPlatform().start {
            flock<Nothing, Unit> {
                val node = node("receiver", port)
                // One slot, stuck on its first message: every frame after it waits in the socket, then in the
                // sender's queue.
                val stuck = behaviour<Int, Unit>(Unit) { _, _, _ -> stay().also { release.await() } }
                val counting = behaviour<Int, Unit>(Unit) { _, _, n -> stay().also { pinged.put(n) } }
                node.expose(spawn("slow", stuck, capacity = 1), Codecs.int)
                node.expose(spawn("ping", counting), Codecs.int, Lane.Control)
                ready.countDown()
                done.await()
                release.countDown()
            }
        }
        try {
            ready.await()
            flock<Nothing, Unit> {
                val node = node("sender", freePort())
                val slow = node.remote(Address("receiver@127.0.0.1:$port", "/user/slow", 0), Codecs.int)
                val ping = node.remote(Address("receiver@127.0.0.1:$port", "/user/ping", 0), Codecs.int, Lane.Control)
                repeat(40_000) { slow.tell(it) }
                Thread.sleep(500)

                val sent = TimeSource.Monotonic.markNow()
                ping.tell(0)
                checkNotNull(pinged.poll(5, TimeUnit.SECONDS)) { "the control frame never arrived" }
                sent.elapsedNow() shouldBeLessThan 500.milliseconds

                (1..200).forEach(ping::tell)
                val rest = generateSequence { pinged.poll(5, TimeUnit.SECONDS) }.take(200).toList()
                rest shouldContainExactly (1..200).toList()
            }
        } finally {
            done.countDown()
            receiver.join()
        }
    }
}
