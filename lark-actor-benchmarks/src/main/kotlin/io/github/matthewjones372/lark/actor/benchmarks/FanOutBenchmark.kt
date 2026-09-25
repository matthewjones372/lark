package io.github.matthewjones372.lark.actor.benchmarks

import io.github.matthewjones372.lark.actor.ActorRef
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.pekko.actor.typed.ActorRef as PekkoRef

private const val FAN = 10_000

/**
 * One message to each of [FAN] idle actors, until all are handled: the case a linger before parking would pay
 * once per actor, if an actor that only receives lingered. The row is the whole fan-out.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class FanOutBenchmark {

    private lateinit var lark: List<ActorRef<Hit>>
    private lateinit var pekko: List<PekkoRef<Hit>>

    @Setup(Level.Trial)
    fun spawn(larkSystem: Lark, pekkoSystem: Pekko) {
        lark = (1..FAN).map { larkSystem.actor("fan-$it", larkCounter()) }
        pekko = (1..FAN).map { Adapter.spawn(pekkoSystem.system, pekkoCounter(), "fan-$it") }
    }

    @Benchmark
    fun lark() = fanOut { hit -> lark.forEach { it.tell(hit) } }

    @Benchmark
    fun pekko() = fanOut { hit -> pekko.forEach { it.tell(hit) } }

    private fun fanOut(tellAll: (Hit) -> Unit) {
        val done = CountDownLatch(FAN)
        tellAll(Hit(done))
        done.await()
    }
}
