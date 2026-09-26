package io.github.matthewjones372.lark.actor.remote

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private val password = "lark-test".toCharArray()

private fun store(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
    checkNotNull(TlsTest::class.java.getResourceAsStream("/tls/$name.p12")).use { load(it, password) }
}

/** [name]'s key and certificate, trusting only what [ca] signed. See src/test/resources/tls/make.sh. */
fun tlsFor(name: String, ca: String = "cluster-ca"): Tls = Tls.mutual(store(name), password, store("trusts-$ca"))

private fun nodeNamed(name: String) = Node(name, "127.0.0.1", ServerSocket(0).use { it.localPort })

private fun frameOf(n: Int) = Frame("/user/clinic", 1, byteArrayOf(n.toByte()))

/** What a transport tells its node, as queues and a latch a test waits on. */
private class Told(dropping: Int = 0) : Listener {
    val received = LinkedBlockingQueue<Pair<String, Int>>()
    val dropped = LinkedBlockingQueue<Int>()
    val allDropped = CountDownLatch(dropping)

    override fun received(from: Node, frame: Frame) = received.put(from.name to frame.payload[0].toInt())

    override fun dropped(peer: Node, frame: Frame) {
        dropped.put(frame.payload[0].toInt())
        allDropped.countDown()
    }

    fun take(count: Int) = List(count) { checkNotNull(received.poll(1, TimeUnit.MINUTES)) }
}

class TlsTest {

    private val opened = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closedAfter(): T = also { opened += it }

    @AfterEach
    fun close() = opened.asReversed().forEach(AutoCloseable::close)

    @Test
    fun `two nodes whose certificates one CA signed exchange frames both ways, in order`() {
        val n1 = nodeNamed("n1")
        val n2 = nodeNamed("n2")
        val toldN1 = Told()
        val toldN2 = Told()
        val first = Transport(n1, toldN1, tls = tlsFor("n1")).closedAfter().apply { listen() }
        val second = Transport(n2, toldN2, tls = tlsFor("n2")).closedAfter().apply { listen() }

        (1..100).forEach { first.send(n2, frameOf(it)) }
        (1..100).forEach { second.send(n1, frameOf(it)) }

        toldN2.take(100) shouldBe (1..100).map { "n1" to it }
        toldN1.take(100) shouldBe (1..100).map { "n2" to it }
    }

    @Test
    fun `a peer another CA signed is refused both ways, and what was sent to it is dropped and said to be`() {
        val n2 = nodeNamed("n2")
        val n4 = nodeNamed("n4")
        val toldN2 = Told(dropping = 3)
        val toldN4 = Told(dropping = 3)
        val trusted = Transport(n2, toldN2, retryFrom = 1.milliseconds, tls = tlsFor("n2")).closedAfter()
        val stranger = Transport(n4, toldN4, retryFrom = 1.milliseconds, tls = tlsFor("n4", "other-ca")).closedAfter()
        trusted.listen()
        stranger.listen()

        (1..3).forEach { stranger.send(n2, frameOf(it)) }
        (1..3).forEach { trusted.send(n4, frameOf(it)) }

        toldN4.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
        toldN2.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
        toldN4.dropped.toList() shouldContainExactly listOf(1, 2, 3)
        toldN2.dropped.toList() shouldContainExactly listOf(1, 2, 3)
        toldN2.received.isEmpty() shouldBe true
        toldN4.received.isEmpty() shouldBe true
    }

    @Test
    fun `a peer with no certificate is refused both ways`() {
        val n1 = nodeNamed("n1")
        val plain = nodeNamed("plain")
        val toldN1 = Told(dropping = 2)
        val toldPlain = Told(dropping = 2)
        val secure = Transport(n1, toldN1, retryFrom = 1.milliseconds, tls = tlsFor("n1")).closedAfter()
        val open = Transport(plain, toldPlain, retryFrom = 1.milliseconds).closedAfter()
        secure.listen()
        open.listen()

        (1..2).forEach { open.send(n1, frameOf(it)) }
        (1..2).forEach { secure.send(plain, frameOf(it)) }

        toldPlain.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
        toldN1.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
        toldN1.received.isEmpty() shouldBe true
        toldPlain.received.isEmpty() shouldBe true
    }

    @Test
    fun `a peer that speaks TLS and trusts the CA, but has no certificate of its own, is refused`() {
        val n1 = nodeNamed("n1")
        val anonymous = nodeNamed("anonymous")
        val toldAnonymous = Told(dropping = 2)
        val noKeys = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val secure = Transport(n1, Told(), tls = tlsFor("n1")).closedAfter()
        val bare = Tls.mutual(noKeys, password, store("trusts-cluster-ca"))
        val client = Transport(anonymous, toldAnonymous, retryFrom = 1.milliseconds, tls = bare).closedAfter()
        secure.listen()

        (1..2).forEach { client.send(n1, frameOf(it)) }

        toldAnonymous.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
        toldAnonymous.dropped.toList() shouldContainExactly listOf(1, 2)
    }
}
