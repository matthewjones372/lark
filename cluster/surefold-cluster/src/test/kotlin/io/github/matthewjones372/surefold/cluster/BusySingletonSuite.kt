package io.github.matthewjones372.surefold.cluster

import io.github.matthewjones372.surefold.actor.DeadLetter
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.onDeadLetter
import io.github.matthewjones372.surefold.actor.remote.Codecs
import io.github.matthewjones372.surefold.actor.remote.Node
import io.github.matthewjones372.surefold.actor.remote.node
import io.github.matthewjones372.surefold.actor.stay
import io.github.matthewjones372.surefold.flock
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeRight
import io.github.matthewjones372.surefold.junit.shouldContainExactly
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val unhurried =
    Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1_000.milliseconds)

object BusySingletonSuite : Suite() {
    init {
        test("a burst to a busy singleton is kept in order by its region, which goes on") {
            val port = ServerSocket(0).use { it.localPort }
            val heard = LinkedBlockingQueue<Int>()
            val open = CountDownLatch(1)
            val letters = ConcurrentLinkedQueue<DeadLetter>()

            val during = flock<Nothing, Unit> {
                onDeadLetter(letters::add)
                val seeds = Discovery.static(Node("", "127.0.0.1", port))
                val cluster = cluster(node("solo", port), seeds, unhurried, leaveWithin = Duration.ZERO)
                cluster.await(1.minutes) { v -> v.members.any { it.status == Status.Up } } shouldBe true
                // Its first message holds its step until the burst is all sent, so its mailbox fills behind it.
                val clock = cluster.singleton("clock", Codecs.int) {
                    behaviour<Int, Unit>(Unit) { _, _, n -> stay().also { if (n == 0) open.await() else heard += n } }
                }
                clock.tell(0)
                (1..5_000).forEach(clock::tell)
                open.countDown()

                List(5_000) { heard.poll(1, TimeUnit.MINUTES) } shouldContainExactly (1..5_000).toList()
                clock.tell(5_001)
                heard.poll(1, TimeUnit.MINUTES) shouldBe 5_001
            }

            during shouldBeRight Unit
            letters.filter { it.why != DeadLetter.Why.Unreachable } shouldBe emptyList()
        }
    }
}
