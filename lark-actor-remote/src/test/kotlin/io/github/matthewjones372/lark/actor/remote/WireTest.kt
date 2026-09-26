package io.github.matthewjones372.lark.actor.remote

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** A port nothing is listening on yet: bound and let go, so a node can be named before it listens. */
private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun node(name: String) = Node(name, "127.0.0.1", freePort())

private fun frame(n: Int) = Frame("/user/clinic", 1, byteArrayOf(n.toByte(), (n shr 8).toByte()))

private val Frame.n: Int get() = (payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8)

/** Everything a transport tells its node, as events a test can wait for one at a time. */
private class Heard : Listener {
    val received = LinkedBlockingQueue<Pair<Node, Int>>()
    val connected = LinkedBlockingQueue<Node>()
    val disconnected = LinkedBlockingQueue<Node>()
    val dropped = ConcurrentLinkedQueue<Int>()
    var droppedCount = CountDownLatch(0)

    override fun received(from: Node, frame: Frame) = received.put(from to frame.n)

    override fun connected(peer: Node) = connected.put(peer)

    override fun disconnected(peer: Node) = disconnected.put(peer)

    override fun dropped(peer: Node, frame: Frame) {
        dropped += frame.n
        droppedCount.countDown()
    }

    fun take(count: Int): List<Pair<Node, Int>> = List(count) { checkNotNull(received.poll(1, TimeUnit.MINUTES)) }
}

class WireTest {

    private val opened = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closedAfter(): T = also { opened += it }

    @AfterEach
    fun close() = opened.asReversed().forEach(AutoCloseable::close)

    @Test
    fun `frames cross between two nodes in the order each sent them, both ways`() {
        val one = node("one")
        val two = node("two")
        val heardByOne = Heard()
        val heardByTwo = Heard()
        val first = Transport(one, heardByOne).closedAfter().apply { listen() }
        val second = Transport(two, heardByTwo).closedAfter().apply { listen() }

        (1..5_000).forEach { first.send(two, frame(it)) }
        (1..5_000).forEach { second.send(one, frame(it)) }

        heardByTwo.take(5_000) shouldBe (1..5_000).map { one to it }
        heardByOne.take(5_000) shouldBe (1..5_000).map { two to it }
    }

    @Test
    fun `a peer that goes and comes back is connected to again, and what is sent after arrives in order`() {
        val one = node("one")
        val two = node("two")
        val heardByOne = Heard()
        val first = Transport(one, heardByOne, retryFrom = 10.milliseconds).closedAfter()
        val before = Heard()
        val second = Transport(two, before).apply { listen() }
        first.send(two, frame(1))
        before.take(1) shouldContainExactly listOf(one to 1)

        second.close()
        heardByOne.disconnected.poll(1, TimeUnit.MINUTES) shouldBe two
        // The first connection's event, drained before the peer is back, so the next one is the reconnection's.
        heardByOne.connected.clear()
        val after = Heard()
        Transport(two, after).closedAfter().listen()
        heardByOne.connected.poll(1, TimeUnit.MINUTES) shouldBe two
        (2..1_000).forEach { first.send(two, frame(it)) }

        after.take(999) shouldBe (2..1_000).map { one to it }
    }

    @Test
    fun `what is sent to a peer nobody answers for is dropped, and said to be`() {
        val heard = Heard().apply { droppedCount = CountDownLatch(3) }
        val first = Transport(node("one"), heard, retryFrom = 1.milliseconds).closedAfter()

        val nobody = node("nobody")
        (1..3).forEach { first.send(nobody, frame(it)) }

        heard.droppedCount.await(1, TimeUnit.MINUTES) shouldBe true
        heard.dropped.toList() shouldContainExactly listOf(1, 2, 3)
    }

    @Test
    fun `while a peer has not yet answered, frames wait up to the room, and the rest are dropped at once`() {
        // Accepts and says nothing, so the handshake never finishes and every frame sent waits for it.
        val silent = ServerSocket(0).closedAfter()
        val accepted = Thread.ofVirtual().start { silent.accept() }
        val heard = Heard()
        val first = Transport(node("one"), heard, room = 4).closedAfter()

        (1..10).forEach { first.send(Node("silent", "127.0.0.1", silent.localPort), frame(it)) }

        heard.dropped.toList() shouldContainExactly (5..10).toList()
        accepted.join()
    }

    @Test
    fun `a node names itself as name at host and port, and reads back the same`() {
        Node.parse("shop-1@10.0.0.7:25520") shouldBe Node("shop-1", "10.0.0.7", 25520)
        Node("shop-1", "10.0.0.7", 25520).toString() shouldBe "shop-1@10.0.0.7:25520"
    }
}
