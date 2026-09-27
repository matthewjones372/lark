package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** Spec 0096: seeds as config writes them, and a way to join found by its name. */
class JoiningTest {

    @Test
    fun `a seed written host and port is whichever node answers there`() {
        Node.at("10.0.0.7:25520") shouldBe Node("", "10.0.0.7", 25520)
        Node.at(" bank-1.bank:25520 ") shouldBe Node("", "bank-1.bank", 25520)
        Discovery.static("a:1", "b:2").seeds() shouldContainExactly listOf(Node("", "a", 1), Node("", "b", 2))
    }

    @Test
    fun `a seed without a port, or with one no node listens on, is refused`() {
        listOf("10.0.0.7", "10.0.0.7:", ":25520", "host:0", "host:70000", "host:port").forEach { bad ->
            shouldThrow<IllegalArgumentException> { Node.at(bad) }.message shouldContain bad
        }
    }

    @Test
    fun `static, dns and srv are on every classpath lark-cluster is`() {
        Joins.available().keys shouldContainAll listOf("static", "dns", "srv")
    }

    @Test
    fun `static joins through its seeds`() {
        val options = JoinOptions(25520, 20.seconds, mapOf("seeds" to listOf("a:1", "b:2")), "lark.cluster.static")
        Joins.named("static", options).use { joining ->
            joining.discovery.seeds() shouldContainExactly listOf(Node("", "a", 1), Node("", "b", 2))
        }
    }

    @Test
    fun `a value a backend needs and was not given is refused with the path it is read at`() {
        shouldThrow<IllegalArgumentException> {
            Joins.named("static", JoinOptions(25520, 20.seconds, emptyMap(), "lark.cluster.static"))
        }.message shouldBe "lark.cluster.static.seeds is missing"
        shouldThrow<IllegalArgumentException> {
            val options = mapOf("name" to "x", "port" to "high")
            Joins.named("dns", JoinOptions(25520, 20.seconds, options, "lark.cluster.dns"))
        }.message shouldBe "lark.cluster.dns.port is not a number"
    }

    @Test
    fun `a backend whose module is not on the classpath is refused, naming the module`() {
        shouldThrow<IllegalArgumentException> { Joins.named("kubernetes", JoinOptions(25520, 20.seconds)) }
            .message shouldBe "join = kubernetes needs lark-cluster-kubernetes on the classpath"
        shouldThrow<IllegalArgumentException> { Joins.named("carrier-pigeon", JoinOptions(25520, 20.seconds)) }
            .message shouldBe "join = carrier-pigeon is none of [dns, srv, static]"
    }

    @Test
    fun `closing a joining releases what it opened, once asked`() {
        var released = 0
        Joining(Discovery.static(), Downing.keepMajority()) { released++ }.use { released shouldBe 0 }
        released shouldBe 1
    }
}
