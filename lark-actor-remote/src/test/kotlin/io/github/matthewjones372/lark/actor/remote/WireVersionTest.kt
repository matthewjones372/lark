package io.github.matthewjones372.lark.actor.remote

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private const val MAGIC = 0x4C41524B

/** What a transport hands its node, kept whole: the carried map is what is under test. */
private class Frames : Listener {
    val received = LinkedBlockingQueue<Frame>()

    override fun received(from: Node, frame: Frame) = received.put(frame)

    fun next(): Frame = checkNotNull(received.poll(1, TimeUnit.MINUTES)) { "no frame arrived" }
}

/**
 * A node built before spec 0122, written out by hand as the wire stood: it speaks version 1 alone, closes on any other
 * offer without answering, and reads frames with no map in them.
 */
private class OldNode : AutoCloseable {
    private val server = ServerSocket(0)
    val node = Node("old", "127.0.0.1", server.localPort)
    val payloads = LinkedBlockingQueue<List<Byte>>()
    val refused = LinkedBlockingQueue<Int>()
    private val accepting = Thread.ofVirtual().start {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@start
            Thread.ofVirtual().start { serve(socket) }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        input.readInt() shouldBe MAGIC
        val version = input.readInt()
        if (version != 1) return refused.put(version)
        input.readUTF()
        input.readLong()
        output.oldHello(node)
        runCatching {
            while (true) {
                val size = input.readInt()
                val path = ByteArray(input.readInt()).also(input::readFully)
                input.readLong()
                payloads.put(ByteArray(size - Int.SIZE_BYTES - path.size - Long.SIZE_BYTES).also(input::readFully).toList())
            }
        }
    }

    override fun close() {
        server.close()
        accepting.join()
    }
}

private fun DataOutputStream.oldHello(self: Node) {
    writeInt(MAGIC)
    writeInt(1)
    writeUTF(self.toString())
    writeLong(1)
    flush()
}

/** Spec 0122's wire: version 2 carries the map, and a node still talks to one that speaks only version 1. */
class WireVersionTest {

    private val opened = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closedAfter(): T = also { opened += it }

    @AfterEach
    fun close() = opened.asReversed().forEach(AutoCloseable::close)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `between two nodes on version 2, what a frame carried arrives with it`() {
        val two = Node("two", "127.0.0.1", freePort())
        val heard = Frames()
        Transport(two, heard).closedAfter().listen()
        val one = Transport(Node("one", "127.0.0.1", freePort()), Frames()).closedAfter()

        val carried = mapOf("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        one.send(two, Frame("/user/clinic", 1, byteArrayOf(7, 8), carried = carried))
        one.send(two, Frame("/user/clinic", 1, byteArrayOf(9)))

        heard.next().let { it.carried shouldBe carried; it.payload.toList() shouldBe listOf<Byte>(7, 8) }
        heard.next().let { it.carried shouldBe emptyMap(); it.payload.toList() shouldBe listOf<Byte>(9) }
    }

    @Test
    fun `a node built before reads what a new node sends it, on version 1, without the map`() {
        val old = OldNode().closedAfter()
        val one = Transport(Node("one", "127.0.0.1", freePort()), Frames(), retryFrom = 10.milliseconds).closedAfter()

        one.send(old.node, Frame("/user/clinic", 1, byteArrayOf(1, 2, 3), carried = mapOf("traceparent" to "00-x-y-01")))

        old.refused.poll(1, TimeUnit.MINUTES) shouldBe 2
        old.payloads.poll(1, TimeUnit.MINUTES) shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `a node built before is answered on version 1, and what it sends is read`() {
        val two = Node("two", "127.0.0.1", freePort())
        val heard = Frames()
        Transport(two, heard).closedAfter().listen()

        Socket().use { socket ->
            socket.connect(InetSocketAddress(two.host, two.port))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            output.oldHello(Node("old", "127.0.0.1", 1))
            input.readInt() shouldBe MAGIC
            input.readInt() shouldBe 1
            val path = "/user/clinic".toByteArray()
            output.writeInt(Int.SIZE_BYTES + path.size + Long.SIZE_BYTES + 2)
            output.writeInt(path.size)
            output.write(path)
            output.writeLong(1)
            output.write(byteArrayOf(4, 5))
            output.flush()

            heard.next().let { it.payload.toList() shouldBe listOf<Byte>(4, 5); it.carried shouldBe emptyMap() }
        }
    }
}
