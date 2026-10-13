package io.github.matthewjones372.lark.cluster

import arrow.core.right
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

class ViewOrderTest {

    @Test
    fun `whoever waits on a view wakes only once every viewer has been told it`() {
        val port = ServerSocket(0).use { it.localPort }
        val self = Node("v1", "127.0.0.1", port)
        val next = View(listOf(Member(self, 1, Status.Up, 1)), emptySet(), self)
        val viewing = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val viewerDone = AtomicBoolean(false)

        val wokeAfterViewer = flock<Nothing, Boolean> {
            val cluster = Cluster(self, node("v1", port), this)
            // A viewer that is slow to take the view in, as a region with a full mailbox is.
            cluster.onView { view ->
                if (view == next) {
                    viewing.countDown()
                    letGo.await()
                    viewerDone.set(true)
                }
            }
            val waiter = Thread.ofVirtual().start { cluster.await(1.minutes) { it == next } }
            val publisher = Thread.ofVirtual().start { cluster.publish(next) }
            viewing.await()
            // The viewer is still holding the view: a waiter that wakes now would act on a view a region lacks.
            val wokeEarly = waiter.join(Duration.ofMillis(200))
            letGo.countDown()
            publisher.join()
            waiter.join()
            !wokeEarly && viewerDone.get()
        }

        wokeAfterViewer shouldBe true.right()
    }
}
