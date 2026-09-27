package io.github.matthewjones372.lark.actor.remote

import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.Histogram
import io.github.matthewjones372.lark.Metrics
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.DeadLetter
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onDeadLetter
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.metrics
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Metrics kept by name and tags, which `CapturedMetrics` does not tell apart, with a wait on a gauge's value. */
private class TaggedMetrics : Metrics {
    private val counters = ConcurrentHashMap<Pair<String, Map<String, String>>, DoubleAdder>()
    private val gauges = HashMap<Pair<String, Map<String, String>>, Double>()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val adder = counters.computeIfAbsent(name to tags) { DoubleAdder() }
        return Counter { adder.add(it) }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { value ->
        lock.withLock {
            gauges[name to tags] = value
            changed.signalAll()
        }
    }

    override fun histogram(name: String, tags: Map<String, String>): Histogram = Histogram { }

    fun counter(name: String, vararg tags: Pair<String, String>): Double = counters[name to tags.toMap()]?.sum() ?: 0.0

    /** Whether the gauge came to read [value] within a minute. */
    fun awaitGauge(name: String, value: Double, vararg tags: Pair<String, String>): Boolean = lock.withLock {
        var left = TimeUnit.MINUTES.toNanos(1)
        while (gauges[name to tags.toMap()] != value) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        true
    }
}

private fun openPort(): Int = ServerSocket(0).use { it.localPort }

class RemoteMetricsTest {

    @Test
    fun `frames are counted each way per peer, and a peer that goes reads 0 and counts what was dropped for it`() {
        val herePort = openPort()
        val therePort = openPort()
        val here = TaggedMetrics()
        val there = TaggedMetrics()
        val heard = CountDownLatch(10)
        val listening = CountDownLatch(1)
        val done = CountDownLatch(1)
        val other = Thread.ofPlatform().start {
            metrics.locally(there) {
                flock<Nothing, Unit> {
                    val node = node("there", therePort)
                    node.expose(
                        spawn(
                            "echo",
                            behaviour<String, Unit>(Unit) { _, _, _ ->
                                stay().also {
                                    heard.countDown()
                                }
                            },
                        ),
                        Codecs.string,
                    )
                    listening.countDown()
                    done.await()
                }
            }
        }
        listening.await()
        val dropped = CountDownLatch(3)

        metrics.locally(here) {
            flock<Nothing, Unit> {
                onDeadLetter { letter -> if (letter.why == DeadLetter.Why.Unreachable) dropped.countDown() }
                val node = node("here", herePort)
                val peer = Node("there", "127.0.0.1", therePort)
                val echo = node.remote(Address(peer.toString(), "/user/echo", 0), Codecs.string)
                repeat(10) { echo.tell("frame $it") }
                heard.await(1, TimeUnit.MINUTES) shouldBe true
                here.awaitGauge("lark.remote.connected", 1.0, "node" to "here", "peer" to "$peer") shouldBe true

                done.countDown()
                other.join()
                here.awaitGauge("lark.remote.connected", 0.0, "node" to "here", "peer" to "$peer") shouldBe true
                repeat(3) { echo.tell("lost $it") }
                dropped.await(1, TimeUnit.MINUTES) shouldBe true

                here.counter("lark.remote.frames", "node" to "here", "peer" to "$peer", "direction" to "out") shouldBe
                    13.0
                here.counter("lark.remote.dropped", "node" to "here", "peer" to "$peer") shouldBe 3.0
            }
        }
        val from = Node("here", "127.0.0.1", herePort)
        there.counter("lark.remote.frames", "node" to "there", "peer" to "$from", "direction" to "in") shouldBe 10.0
    }
}
