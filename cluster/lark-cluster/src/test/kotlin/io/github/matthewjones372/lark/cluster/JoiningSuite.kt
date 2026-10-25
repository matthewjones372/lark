package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.forAll
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldContainAll
import io.github.matthewjones372.lark.junit.shouldContainExactly
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.resource
import kotlin.time.Duration.Companion.seconds

/** Spec 0096: seeds as config writes them, and a way to join found by its name. */
object JoiningSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a seed written host and port is whichever node answers there") { _ ->
            (Node.at("10.0.0.7:25520") shouldBe Node("", "10.0.0.7", 25520)) and
                (Node.at(" bank-1.bank:25520 ") shouldBe Node("", "bank-1.bank", 25520)) and
                (Discovery.static("a:1", "b:2").seeds() shouldContainExactly listOf(Node("", "a", 1), Node("", "b", 2)))
        }

        test("a seed without a port, or with one no node listens on, is refused") { _ ->
            listOf("10.0.0.7", "10.0.0.7:", ":25520", "host:0", "host:70000", "host:port").forAll { bad ->
                shouldThrow<IllegalArgumentException>({ Node.at(bad) }) { e -> e.message shouldContain bad }
            }
        }

        test("static, dns and srv are on every classpath lark-cluster is") { _ ->
            Joins.available().keys shouldContainAll listOf("static", "dns", "srv")
        }

        test("static joins through its seeds") { _ ->
            val options = JoinOptions(25520, 20.seconds, mapOf("seeds" to listOf("a:1", "b:2")), "lark.cluster.static")
            Joins.named("static", options).use { joining ->
                joining.discovery.seeds() shouldContainExactly listOf(Node("", "a", 1), Node("", "b", 2))
            }
        }

        test("a value a backend needs and was not given is refused with the path it is read at") { _ ->
            val missing = shouldThrow<IllegalArgumentException>({
                Joins.named("static", JoinOptions(25520, 20.seconds, emptyMap(), "lark.cluster.static"))
            }) { e -> e.message shouldBe "lark.cluster.static.seeds is missing" }
            val notANumber = shouldThrow<IllegalArgumentException>({
                val options = mapOf("name" to "x", "port" to "high")
                Joins.named("dns", JoinOptions(25520, 20.seconds, options, "lark.cluster.dns"))
            }) { e -> e.message shouldBe "lark.cluster.dns.port is not a number" }
            missing and notANumber
        }

        test("a backend whose module is not on the classpath is refused, naming the module") { _ ->
            val kubernetes = shouldThrow<IllegalArgumentException>({
                Joins.named("kubernetes", JoinOptions(25520, 20.seconds))
            }) { e -> e.message shouldBe "join = kubernetes needs lark-cluster-kubernetes on the classpath" }
            val pigeon = shouldThrow<IllegalArgumentException>({
                Joins.named("carrier-pigeon", JoinOptions(25520, 20.seconds))
            }) { e -> e.message shouldBe "join = carrier-pigeon is none of [dns, srv, static]" }
            kubernetes and pigeon
        }

        test("closing a joining releases what it opened, once asked") { _ ->
            var released = 0
            val before = Joining(Discovery.static(), Downing.keepMajority()) { released++ }.use { released shouldBe 0 }
            before and (released shouldBe 1)
        }

        test("a list given as numbered keys, as system properties give one, is read in its numbers' order") { _ ->
            val numbered = mapOf("seeds" to mapOf("1" to "b:2", "0" to "a:1", "10" to "c:3"))
            val ordered = Joins.named("static", JoinOptions(25520, 20.seconds, numbered)).use { joining ->
                joining.discovery.seeds() shouldContainExactly
                    listOf(Node("", "a", 1), Node("", "b", 2), Node("", "c", 3))
            }
            val notAList = shouldThrow<IllegalArgumentException>({
                Joins.named("static", JoinOptions(25520, 20.seconds, mapOf("seeds" to mapOf("first" to "a:1")), "at"))
            }) { e -> e.message shouldBe "at.seeds is not a list" }
            ordered and notAList
        }
    }
}
