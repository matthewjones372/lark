package io.github.matthewjones372.lark.cluster

import arrow.core.right
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private sealed interface Tab

private data class Spend(val pence: Int) : Tab

/** Answers with the node the tab runs on and what has been spent on it there. */
private data class Bill(val reply: Reply<String>) : Tab

private val tabCodec = object : MessageCodec<Tab> {
    override fun write(message: Tab, out: WireOut) = when (message) {
        is Spend -> {
            out.int(1)
            out.int(message.pence)
        }

        is Bill -> {
            out.int(2)
            out.reply(message.reply, Codecs.string)
        }
    }

    override fun read(input: WireIn): Tab = when (val tag = input.int()) {
        1 -> Spend(input.int())
        2 -> Bill(input.reply(Codecs.string))
        else -> error("no tab message has the tag $tag")
    }
}

/** How many of each tab run now, across every node in this JVM; and whether any ever ran twice at once. */
private val runningTabs = ConcurrentHashMap<String, AtomicInteger>()
private val twice = AtomicBoolean(false)

private fun tab(on: String, id: String) = behaviour<Tab, Int>(0) { _, spent, message ->
    when (message) {
        is Spend -> become(spent + message.pence)
        is Bill -> stay().also { message.reply("$on:$spent") }
    }
}.onStart {
    if (runningTabs.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet() > 1) twice.set(true)
}.onSignal { _, _, signal ->
    if (signal == Signal.Stopping) runningTabs.getValue(id).decrementAndGet()
    stay()
}

private val brisk =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun openPort(): Int = ServerSocket(0).use { it.localPort }

/** A node with the tabs sharded on it, on a flock and a thread of its own, until [close]. */
private class Bar(val name: String, port: Int, seeds: Discovery) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sharded<Tab>>>()
    private val thread = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            val cluster = cluster(node(name, port), seeds, brisk)
            opened.set(cluster to cluster.sharding("tab", tabCodec, passivateAfter = 1.minutes) { tab(name, it) })
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get().first
        }

    val tabs: Sharded<Tab>
        get() {
            ready.await()
            return opened.get().second
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

private fun List<Bar>.awaitUp(count: Int) = forEach { bar ->
    withClue("${bar.name} sees $count Up") {
        bar.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == count } shouldBe true
    }
}

class ShardingTest {

    @Test
    fun `an entity told and asked from any node runs on the one node that owns it`() {
        val ports = List(3) { openPort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val bars = ports.mapIndexed { i, port -> Bar("n${i + 1}", port, seeds) }
        try {
            bars.forEach { bar ->
                bar.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == 3 } shouldBe
                    true
            }
            val shard = Placement.shardOf("t-42", Sharding.SHARDS)
            val owner = Placement.owner("tab", shard, bars.first().cluster.view.members)?.name

            // Each node tells, then asks on the same way through: its bill counts its own spend and every one before.
            val bills = bars.map { bar ->
                val tab = bar.tabs.entity("t-42")
                tab.tell(Spend(10))
                tab.ask<Tab, String>(1.minutes) { Bill(it) }
            }

            bills shouldBe listOf("$owner:10".right(), "$owner:20".right(), "$owner:30".right())
        } finally {
            bars.forEach(Bar::close)
        }
    }

    @Test
    fun `a node joining and one leaving while tabs are told throughout never runs one tab on two nodes`() {
        val ports = List(4) { openPort() }
        val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
        val bars = ports.take(3).mapIndexed { i, port -> Bar("n${i + 1}", port, seeds) }.toMutableList()
        val unanswered = ConcurrentLinkedQueue<String>()
        val stop = AtomicBoolean(false)
        try {
            bars.awaitUp(3)
            val tabs = bars.first().tabs
            // Tells and asks every tab from the first node for as long as the membership is changing.
            val traffic = Thread.ofVirtual().start {
                while (!stop.get()) {
                    (1..200).map { "t-$it" }.forEach { id ->
                        tabs.entity(id).tell(Spend(1))
                        if (tabs.entity(id).ask<Tab, String>(1.minutes) { Bill(it) }.isLeft()) unanswered += id
                    }
                }
            }

            bars += Bar("n4", ports[3], seeds)
            bars.awaitUp(4)
            bars[1].cluster.leave()
            bars.filter { it.name != "n2" }.forEach { bar ->
                bar.cluster.await(1.minutes) { view -> view.members.none { it.node.name == "n2" } } shouldBe true
            }
            stop.set(true)
            traffic.join()

            withClue("a tab ran on two nodes at once") { twice.get() shouldBe false }
            unanswered.toList().shouldBeEmpty()
        } finally {
            stop.set(true)
            bars.forEach(Bar::close)
        }
    }
}
