package io.github.matthewjones372.lark.stream.benchmarks

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.stream.Actors
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * The `Actors` backend on a flock held open for the whole trial, on a thread of its own, since an invocation cannot
 * sit inside one: a service's flock, started once and run on many times, as `Pekko` is its actor system.
 */
@State(Scope.Benchmark)
open class Flocked {

    lateinit var actors: Actors

    private val release = CountDownLatch(1)
    private lateinit var holder: Thread

    @Setup(Level.Trial)
    fun start() {
        val opened = AtomicReference<Flock<Nothing>>()
        val ready = CountDownLatch(1)
        holder = Thread.ofPlatform().start {
            flock<Nothing, Unit> {
                opened.set(this)
                ready.countDown()
                release.await()
            }
        }
        ready.await()
        actors = Actors(opened.get())
    }

    @TearDown(Level.Trial)
    fun stop() {
        release.countDown()
        holder.join()
    }
}
