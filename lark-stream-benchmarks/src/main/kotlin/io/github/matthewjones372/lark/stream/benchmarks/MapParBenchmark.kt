package io.github.matthewjones372.lark.stream.benchmarks

import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.mapPar
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

private const val ELEMENTS = 10_000
private const val PARALLELISM = 8

/** A cheap body forked per element, so the row is the cost of the fork and the reordering, not the body. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(ELEMENTS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class MapParBenchmark {

    private val ints = (0 until ELEMENTS).toList()

    @Benchmark
    fun lark(pekko: Pekko): Long =
        Stream.from(ints)
            .mapPar(PARALLELISM) { it.toLong() * 3 }
            .runFold(0L) { total, n -> total + n }
            .run(pekko.system)
            .done()
}
