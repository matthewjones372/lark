package io.github.matthewjones372.lark.stream.benchmarks

import io.github.matthewjones372.lark.stream.Layout
import io.github.matthewjones372.lark.stream.Measured
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.github.matthewjones372.lark.stream.Profiler
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.measured
import io.github.matthewjones372.lark.stream.render
import io.github.matthewjones372.lark.stream.run
import org.apache.pekko.actor.ActorSystem
import java.io.File

/**
 * Each benchmark's pipeline, run once measured on Pekko, drawn as Mermaid and as text with where its time
 * went: `./gradlew :lark-stream-benchmarks:profiles`, which writes them to `build/profiles`.
 */
fun main(args: Array<String>) {
    val out = File(args.single()).apply { mkdirs() }
    val system = ActorSystem.create("lark-stream-profiles")
    try {
        val pipelines: Map<String, Run<*, Any>> = mapOf(
            "ChainBenchmark" to chainPipeline(chainInts()),
            "MapParBenchmark" to parallelPipeline(parallelInts()),
            "GroupedWithinBenchmark" to batchedPipeline(batchedInts()),
            "RunManyBenchmark" to perRequestPipeline(),
        )
        pipelines.forEach { (name, run) -> profiled(name, run, system, out) }
    } finally {
        system.terminate()
        system.getWhenTerminated().toCompletableFuture().join()
    }
}

private fun profiled(name: String, run: Run<*, Any>, system: ActorSystem, out: File) {
    val profiler = Profiler()
    // Every element sampled: this is a picture of where time goes, not a number a benchmark reports.
    run.measured(Measured(name, profiler, sampleEvery = 1)).run(PekkoStreams(system)).done()
    val profile = profiler.profile()
    out.resolve("$name.mmd").writeText(run.render(Layout.Mermaid, profile = profile) + "\n")
    out.resolve("$name.txt").writeText(run.render(profile = profile) + "\n")
}
