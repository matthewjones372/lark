package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.RemoteNode
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.watch
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.forAll
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeGreaterThanOrEqual
import io.github.matthewjones372.lark.junit.shouldBeLessThan
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val quick =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** A node on a flock and a thread of its own, until [close]. */
private class Running(
    name: String,
    port: Int,
    seeds: Discovery,
    leaveWithin: Duration = 30.seconds,
    admitted: Ready = Ready.AtOnce,
    setup: Flock<Nothing>.(RemoteNode) -> Unit = {},
) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val joined = AtomicReference<Cluster>()
    private val thread = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            val node = node(name, port)
            setup(node)
            joined.set(cluster(node, seeds, quick, leaveWithin = leaveWithin, ready = admitted))
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return joined.get()
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

private fun View.upNames() = members.filter { it.status == Status.Up }.map { it.node.name }.toSet()

object ClusterSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test(
            "five nodes in one JVM agree on the same view, and one that stops is seen unreachable by all the rest",
        ) { _ ->
            val ports = List(5) { freePort() }
            val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
            val names = (1..5).map { "n$it" }
            // Each goes as a crashed node does, without leaving: the fifth to be seen unreachable, and the rest because
            // a leave waits on a cluster that has not yet downed it.
            val nodes = names.zip(ports).map { (name, port) -> Running(name, port, seeds, leaveWithin = Duration.ZERO) }
            try {
                val seen = nodes.map { node ->
                    val up = withClue("${node.cluster.self} sees all five Up") {
                        node.cluster.await(1.minutes) { it.upNames() == names.toSet() } shouldBe true
                    }
                    up to node.cluster.view.members
                }
                val allUp = seen.forAll { (up, _) -> up }
                val views = seen.map { (_, members) -> members }
                val agreed = views.distinct().size shouldBe 1
                // The lowest seed forms the cluster, so it is the oldest member.
                val oldest = names[ports.indexOf(ports.take(3).min())]
                val led = nodes.map { it.cluster.view.leader?.name }.distinct() shouldBe listOf(oldest)

                nodes.last().close()

                allUp and agreed and led and nodes.dropLast(1).forAll { node ->
                    withClue("${node.cluster.self} sees n5 unreachable") {
                        node.cluster.await(1.minutes) { view -> view.unreachable.any { it.name == "n5" } } shouldBe true
                    }
                }
            } finally {
                nodes.forEach(Running::close)
            }
        }

        test(
            "a subscriber hears each member come Up, and a member that leaves is removed and ends every watch on it",
        ) { _ ->
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val second = Running("n2", ports[1], seeds)
            val third = Running("n3", ports[2], seeds) { node ->
                node.expose(spawn("ward", behaviour<String, Unit>(Unit) { _, _, _ -> stay() }), Codecs.string)
            }
            try {
                flock<Nothing, Expectations> {
                    val heard = LinkedBlockingQueue<MemberEvent>()
                    val node = node("n1", ports[0])
                    val cluster = cluster(node, seeds, quick)
                    val listener =
                        behaviour<MemberEvent, Unit>(Unit) { _, _, event -> stay().also { heard.put(event) } }
                    cluster.subscribe(spawn("listener", listener))
                    fun <E : MemberEvent> next(type: Class<E>): E =
                        generateSequence { heard.poll(1, TimeUnit.MINUTES) }.filterIsInstance(type).first()
                    val ups = List(3) { next(MemberEvent.Up::class.java).member.node.name }.toSet() shouldBe
                        setOf("n1", "n2", "n3")

                    val ward = node.remote(Address("n3@127.0.0.1:${ports[2]}", "/user/ward", 0), Codecs.string)
                    val watched = watch(ward)
                    third.cluster.leave()

                    ups and
                        (next(MemberEvent.Removed::class.java).member.node.name shouldBe "n3") and
                        (watched.await().ref shouldBe ward)
                }.shouldBeRight { it }
            } finally {
                second.close()
                third.close()
            }
        }

        test(
            "a subscriber too busy to take its events hears every one later, in order, and the view moves on meanwhile",
        ) { _ ->
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val second = Running("n2", ports[1], seeds)
            val third = Running("n3", ports[2], seeds)
            try {
                flock<Nothing, Expectations> {
                    val heard = LinkedBlockingQueue<MemberEvent>()
                    val busy = CountDownLatch(1)
                    val cluster = cluster(node("n1", ports[0]), seeds, quick)
                    // One slot, and stuck on its first event: every event after the next finds its mailbox full.
                    val slow = behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
                        busy.await()
                        stay().also { heard.put(event) }
                    }
                    cluster.subscribe(spawn("slow", slow, capacity = 1))

                    val moved = withClue("the cluster's view, with its subscriber full") {
                        cluster.await(30.seconds) { it.upNames() == setOf("n1", "n2", "n3") } shouldBe true
                    }
                    busy.countDown()
                    val ups = List(3) { generateSequence { heard.poll(1, TimeUnit.MINUTES) }.first() }
                    moved and
                        (ups.map { it::class } shouldBe List(3) { MemberEvent.Up::class }) and
                        (ups.map { it.member.node.name }.toSet() shouldBe setOf("n1", "n2", "n3"))
                }.shouldBeRight { it }
            } finally {
                second.close()
                third.close()
            }
        }

        test("a node joined to wait is Joining and not ready until it is admitted, and Up and ready after") { _ ->
            val ports = List(2) { freePort() }.sorted()
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val nodes =
                listOf(Running("n1", ports[0], seeds), Running("n2", ports[1], seeds, admitted = Ready.WhenTold))
            try {
                val (up, waiting) = nodes
                val joined =
                    up.cluster.await(1.minutes) { view -> view.members.any { it.node.name == "n2" } } shouldBe true
                val notUp = up.cluster.await(2.seconds) { "n2" in it.upNames() } shouldBe false
                val notReady = waiting.cluster.ready() shouldBe false

                waiting.cluster.admit()

                val bothUp = nodes.forAll { node ->
                    node.cluster.await(1.minutes) { it.upNames() == setOf("n1", "n2") } shouldBe true
                }
                joined and notUp and notReady and bothUp and (waiting.cluster.ready() shouldBe true)
            } finally {
                nodes.forEach(Running::close)
            }
        }

        test("a node whose flock closes leaves, and the others remove it without downing it") { _ ->
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val nodes = ports.mapIndexed { i, port -> Running("n${i + 1}", port, seeds) }
            try {
                val formed =
                    nodes.forAll { node -> node.cluster.await(1.minutes) { it.upNames().size == 3 } shouldBe true }
                val seen = ConcurrentLinkedQueue<Status>()
                nodes.take(2).forEach { node ->
                    node.cluster.onView { view ->
                        seen += view.members.filter { it.node.name == "n3" }.map { it.status }
                    }
                }

                val closing = TimeSource.Monotonic.markNow()
                nodes.last().close()

                // It learns it is out as soon as it is, and does not wait out its deadline.
                val prompt = closing.elapsedNow() shouldBeLessThan 10.seconds
                // Removed well inside the 20 seconds a crashed node waits before it is even downed.
                val removed = nodes.take(2).forAll { node ->
                    node.cluster.await(10.seconds) { view -> view.members.none { it.node.name == "n3" } } shouldBe true
                }
                formed and prompt and removed and (seen.toSet() shouldBe setOf(Status.Up, Status.Leaving))
            } finally {
                nodes.forEach(Running::close)
            }
        }

        test("a node that cannot finish leaving closes by its deadline") { _ ->
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val nodes = ports.mapIndexed { i, port ->
                Running("n${i + 1}", port, seeds, leaveWithin = if (i == 0) 2.seconds else Duration.ZERO)
            }
            try {
                val formed =
                    nodes.forAll { node -> node.cluster.await(1.minutes) { it.upNames().size == 3 } shouldBe true }
                nodes.drop(1).forEach(Running::close)
                val unreachable = nodes.first().cluster.await(1.minutes) { it.unreachable.size == 2 } shouldBe true

                // With the other two unreachable and not yet downed, no leader can remove it.
                val started = TimeSource.Monotonic.markNow()
                nodes.first().close()

                formed and unreachable and
                    (started.elapsedNow() shouldBeGreaterThanOrEqual 2.seconds) and
                    (started.elapsedNow() shouldBeLessThan 10.seconds)
            } finally {
                nodes.forEach(Running::close)
            }
        }

        test(
            "a node restarted at its address comes Up as a new life, and hears its earlier life downed as not itself",
        ) { _ ->
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            // Every node goes without leaving: a leave would wait on a cluster that still holds a gone node.
            val first = Running("n1", ports[0], seeds, leaveWithin = Duration.ZERO)
            val second = Running("n2", ports[1], seeds, leaveWithin = Duration.ZERO)
            // Gone as a crashed node goes, without leaving, so the others still hold its life when it comes back.
            val formed = Running("n3", ports[2], seeds, leaveWithin = Duration.ZERO).use { earlier ->
                earlier.cluster.await(30.seconds) { it.upNames() == setOf("n1", "n2", "n3") } shouldBe true
            }
            try {
                formed and flock<Nothing, Expectations> {
                    val heard = LinkedBlockingQueue<MemberEvent>()
                    val cluster = cluster(node("n3", ports[2]), seeds, quick, leaveWithin = Duration.ZERO)
                    val listener =
                        behaviour<MemberEvent, Unit>(Unit) { _, _, event -> stay().also { heard.put(event) } }
                    cluster.subscribe(spawn("listener", listener))

                    val downed = generateSequence { heard.poll(1, TimeUnit.MINUTES) }
                        .filterIsInstance<MemberEvent.Downed>().first { it.member.node == cluster.self }
                    val notItself = withClue("the earlier life's downing names this address, and is not this life's") {
                        cluster.isSelf(downed.member) shouldBe false
                    }
                    fun upAsItself(view: View) = view.members.any { cluster.isSelf(it) && it.status == Status.Up }
                    notItself and
                        (cluster.await(1.minutes, ::upAsItself) shouldBe true) and
                        (cluster.ready() shouldBe true) and
                        (heard.filterIsInstance<MemberEvent.Downed>().none { cluster.isSelf(it.member) } shouldBe true)
                }.shouldBeRight { it }
            } finally {
                first.close()
                second.close()
            }
        }

        test("watches end with the life they were made on, not with a later life at the same address") { _ ->
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val second = Running("n2", ports[1], seeds, leaveWithin = Duration.ZERO)
            fun ward(): Flock<Nothing>.(RemoteNode) -> Unit = { node ->
                node.expose(spawn("ward", behaviour<String, Unit>(Unit) { _, _, _ -> stay() }), Codecs.string)
            }
            val earlier = Running("n3", ports[2], seeds, leaveWithin = Duration.ZERO, setup = ward())
            try {
                flock<Nothing, Expectations> {
                    val node = node("n1", ports[0])
                    // The default 20 s before a downed life is removed: long enough to watch the later life's actor
                    // first.
                    val cluster = cluster(node, seeds, quick, leaveWithin = Duration.ZERO)
                    val formed = cluster.await(30.seconds) { it.upNames() == setOf("n1", "n2", "n3") } shouldBe true
                    val at = Address("n3@127.0.0.1:${ports[2]}", "/user/ward", 0)
                    val first = watch(node.remote(at, Codecs.string))
                    val earlierUid = cluster.view.members.first { it.node.name == "n3" }.uid

                    earlier.close()
                    val again = Running("n3", ports[2], seeds, leaveWithin = Duration.ZERO, setup = ward())
                    formed and again.use { later ->
                        // The earlier life's watch ends as the later life joins, before the earlier one is removed.
                        val _ = first.await()
                        val beforeRemoved = withClue("the watch ended before the earlier life was removed") {
                            cluster.view.members.any { it.uid == earlierUid } shouldBe true
                        }
                        val laterUp =
                            later.cluster.await(30.seconds) { it.upNames() == setOf("n1", "n2", "n3") } shouldBe true
                        val allUp = cluster.await(30.seconds) { it.upNames() == setOf("n1", "n2", "n3") } shouldBe true

                        val ended = AtomicBoolean(false)
                        val second = watch(node.remote(at, Codecs.string))
                        // Waits until the watch ends, or the test does: the flock interrupts it as it closes.
                        async {
                            try {
                                second.await()
                                ended.set(true)
                            } catch (_: InterruptedException) {
                                Unit
                            }
                        }
                        val removed = cluster.await(1.minutes) { view -> view.members.none { it.uid == earlierUid } }
                        Thread.sleep(1_000)
                        beforeRemoved and laterUp and allUp and (removed shouldBe true) and
                            withClue("the earlier life's removal ends no watch on the later life's actor") {
                                ended.get() shouldBe false
                            }
                    }
                }.shouldBeRight { it }
            } finally {
                second.close()
            }
        }
    }
}
