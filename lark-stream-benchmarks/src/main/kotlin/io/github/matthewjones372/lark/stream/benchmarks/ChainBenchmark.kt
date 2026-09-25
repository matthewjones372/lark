package io.github.matthewjones372.lark.stream.benchmarks

import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.filter
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runFold
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
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

private const val ELEMENTS = 100_000
private const val SCALE = 3L

private data class Odd(val value: Long)

/**
 * Five cheap stages in a row, per element: the shape 0047's merge rewrites.
 *
 * `pekko` is the same five stages written against Pekko by hand, with no guard, so the gap between
 * the rows is what lark's own wrapping costs today.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(ELEMENTS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class ChainBenchmark {

    private val ints = (0 until ELEMENTS).toList()

    private val forks = Forks()

    private fun chain(): Run<Odd, Long> =
        Stream.from(ints)
            .map { it.toLong() }
            .map { it * SCALE }
            .filter { it % 2 == 0L }
            .map { it + 1 }
            .mapOrFail { if (it % 2 == 0L) fail(Odd(it)) else it }
            .runFold(0L) { total, n -> total + n }

    @Benchmark
    fun lark(pekko: Pekko): Long = chain().run(pekko.system).done()

    /** The same description on `Forks`: one pull loop, where Pekko hands each element from stage to stage. */
    @Benchmark
    fun forks(): Long = chain().run(forks).done()

    @Benchmark
    fun pekko(pekko: Pekko): Long =
        Source.from(ints)
            .map { it.toLong() }
            .map { it * SCALE }
            .filter { it % 2 == 0L }
            .map { it + 1 }
            .map { check(it % 2 != 0L); it }
            .runWith(Sink.fold(0L) { total, n -> total + n }, pekko.system)
            .toCompletableFuture()
            .join()
}
