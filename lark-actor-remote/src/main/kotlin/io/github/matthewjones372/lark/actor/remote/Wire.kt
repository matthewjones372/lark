package io.github.matthewjones372.lark.actor.remote

import io.github.matthewjones372.lark.Clock
import io.github.matthewjones372.lark.clock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A node as an address names it: `name@host:port`. */
data class Node(val name: String, val host: String, val port: Int) {
    override fun toString() = "$name@$host:$port"

    companion object {
        private val form = Regex("""([^@]+)@(.+):(\d+)""")

        fun parse(node: String): Node {
            val parts = requireNotNull(form.matchEntire(node)) { "$node is not name@host:port" }.destructured
            val (name, host, port) = parts
            return Node(name, host, port.toInt())
        }
    }
}

/**
 * One message on the wire: the actor it is for, by path and incarnation, and its codec's bytes. On the sending side
 * it keeps the [message] it was written from, so that a frame that is dropped can be a dead letter with it in; that
 * is not written.
 */
class Frame(val path: String, val incarnation: Long, val payload: ByteArray, val message: Any? = null)

/** What a [Transport] tells the node it carries for. Each is called on one of the transport's own threads. */
interface Listener {
    fun received(from: Node, frame: Frame)

    /** [peer] answered the handshake, and what is sent to it now crosses. */
    fun connected(peer: Node) = Unit

    /** The connection to [peer] went down: what was queued for it has been [dropped], and it is being retried. */
    fun disconnected(peer: Node) = Unit

    /** [frame] will not reach [peer]: its connection is down, or its queue was full. At most once, as promised. */
    fun dropped(peer: Node, frame: Frame) = Unit
}

private const val MAGIC = 0x4C41524B // "LARK"
private const val VERSION = 1

/** A frame larger than this is a stream out of step, not a message: the connection is dropped. */
private const val MAX_FRAME = 16 shl 20

/**
 * Frames between this node and others: one connection to each peer it sends to, opened on first use, on JDK sockets
 * and virtual threads. Each direction has its own connection, so order holds per sender and receiver. What is sent
 * to a peer waits, up to [room] frames, while its connection opens; past that, and whenever an attempt to connect
 * fails or a connection drops, frames are dropped and reported. A dropped connection is retried with a backoff from
 * [retryFrom] doubling to [retryUpTo], on the clock the transport was made on.
 */
class Transport(
    val self: Node,
    private val listener: Listener,
    private val room: Int = 8192,
    private val retryFrom: Duration = 100.milliseconds,
    private val retryUpTo: Duration = 5.seconds,
) : AutoCloseable {

    /** This node in this life: a peer that sees another one under the same name knows it restarted. */
    val uid: Long = Random.nextLong()

    private val time: Clock = clock.get()
    private val closed = AtomicBoolean(false)
    private val outbound = ConcurrentHashMap<Node, Outbound>()
    private val inbound = ConcurrentHashMap.newKeySet<Socket>()
    private val server = ServerSocket()
    private var accepting: Thread? = null

    /** Starts accepting peers on [self]'s host and port, 0 for any free one; the port it bound. */
    fun listen(): Int {
        server.bind(InetSocketAddress(self.host, self.port))
        accepting = Thread.ofVirtual().name("lark-remote-accept").start(::accept)
        return server.localPort
    }

    fun send(to: Node, frame: Frame) {
        if (closed.get()) return listener.dropped(to, frame)
        outbound.computeIfAbsent(to, ::Outbound).offer(frame)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        accepting?.join()
        outbound.values.forEach(Outbound::close)
        inbound.forEach(Socket::close)
    }

    private fun accept() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                return
            }
            inbound += socket
            Thread.ofVirtual().name("lark-remote-in").start { serve(socket) }
        }
    }

    /** A peer's connection to this node: its hello, this node's answer, then its frames until it ends. */
    private fun serve(socket: Socket) {
        socket.use {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val peer = input.hello()
                output.hello(self, uid)
                while (!closed.get()) listener.received(peer, input.frame())
            } catch (_: IOException) {
                // The peer went, or sent what is not a lark stream: either way this connection is over.
            } finally {
                inbound -= socket
            }
        }
    }

    /** One peer's queue and the virtual thread that connects to it and writes. */
    private inner class Outbound(private val peer: Node) {
        private val queue = ArrayBlockingQueue<Frame>(room)
        private val writer = Thread.ofVirtual().name("lark-remote-out-$peer").start(::run)

        @Volatile
        private var socket: Socket? = null

        fun offer(frame: Frame) {
            if (!queue.offer(frame)) listener.dropped(peer, frame)
        }

        fun close() {
            socket?.close()
            writer.interrupt()
            writer.join()
            drop()
        }

        private fun run() {
            var wait = retryFrom
            while (!closed.get()) {
                val connected = try {
                    connectAndWrite()
                } catch (_: IOException) {
                    false
                } catch (_: InterruptedException) {
                    false
                }
                if (closed.get()) return
                drop()
                wait = if (connected) retryFrom else minOf(wait * 2, retryUpTo)
                try {
                    time.sleep(wait)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        /** Whether the handshake got through before the connection ended. */
        private fun connectAndWrite(): Boolean {
            val socket = Socket().also { socket = it }
            socket.use {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(peer.host, peer.port))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                output.hello(self, uid)
                val answered = input.hello()
                if (answered.name != peer.name) throw IOException("$peer answered as $answered")
                listener.connected(peer)
                // The peer never writes after its hello: a read that returns is the connection ending.
                val writer = Thread.currentThread()
                val watch = Thread.ofVirtual().start {
                    try {
                        input.read()
                    } catch (_: IOException) {
                        // Closed on either side: the writer finds out the same way.
                    }
                    if (!closed.get()) writer.interrupt()
                }
                try {
                    write(output)
                } catch (_: IOException) {
                    // The connection ended while writing; the watch may have seen it first.
                } catch (_: InterruptedException) {
                    // The watch saw the connection end, or the transport is closing.
                } finally {
                    listener.disconnected(peer)
                    socket.close()
                    watch.join()
                    Thread.interrupted()
                }
            }
            return true
        }

        private fun write(output: DataOutputStream) {
            while (true) {
                output.frame(queue.take())
                if (queue.isEmpty()) output.flush()
            }
        }

        private fun drop() {
            generateSequence { queue.poll() }.forEach { listener.dropped(peer, it) }
        }
    }
}

private fun DataOutputStream.hello(self: Node, uid: Long) {
    writeInt(MAGIC)
    writeInt(VERSION)
    writeUTF(self.toString())
    writeLong(uid)
    flush()
}

private fun DataInputStream.hello(): Node {
    if (readInt() != MAGIC) throw IOException("a connection that does not speak lark")
    val version = readInt()
    if (version != VERSION) throw IOException("a peer on wire version $version, where this node speaks $VERSION")
    val node = Node.parse(readUTF())
    readLong()
    return node
}

private fun DataOutputStream.frame(frame: Frame) {
    val path = frame.path.toByteArray(Charsets.UTF_8)
    writeInt(Int.SIZE_BYTES + path.size + Long.SIZE_BYTES + frame.payload.size)
    writeInt(path.size)
    write(path)
    writeLong(frame.incarnation)
    write(frame.payload)
}

private fun DataInputStream.frame(): Frame {
    val size = readInt()
    if (size !in 0..MAX_FRAME) throw IOException("a frame of $size bytes")
    val path = ByteArray(readInt()).also(::readFully)
    val incarnation = readLong()
    val payload = ByteArray(size - Int.SIZE_BYTES - path.size - Long.SIZE_BYTES).also(::readFully)
    return Frame(String(path, Charsets.UTF_8), incarnation, payload)
}
