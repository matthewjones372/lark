package io.github.matthewjones372.lark.stream.benchmarks

import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.groupedWithin
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runFold
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private const val ELEMENTS = 100_000
private const val BATCH = 100

/** Batches that always fill before the window closes, so the row is the operator and not the clock. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(ELEMENTS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class GroupedWithinBenchmark {

    private val ints = (0 until ELEMENTS).toList()

    @Benchmark
    fun lark(pekko: Pekko): Int =
        Stream.from(ints)
            .groupedWithin(BATCH, 1.seconds)
            .runFold(0) { total, batch -> total + batch.size }
            .run(pekko.system)
            .done()
}
