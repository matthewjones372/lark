package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeEmpty
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource
import java.io.File

/**
 * Each pipeline in RenderPipelines.kt, rendered as text, as optimised text and as Mermaid, held to the
 * golden file of the same name under `src/test/resources/render`. A rendering that differs is written to
 * `build/render-actual`, from where it can be read, and copied over the golden file if it is right.
 */
object RenderSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private val pipelines = mapOf(
        "chain" to chain,
        "parallel" to parallel,
        "batched" to batched,
        "per-request" to perRequest,
        "fan-in" to fanIn,
    )

    private val renderings: Map<String, (Run<*, *>) -> String> = mapOf(
        "txt" to { run -> run.render() },
        "optimised.txt" to { run -> run.render(optimised = true) },
        "mmd" to { run -> run.render(Layout.Mermaid) },
    )

    /** The Mermaid this renderer writes, and nothing else: a header, node declarations and edges. */
    private val mermaidLine = Regex(
        """flowchart TD|    (n\d+|run)\["[^"]*"]|    (n\d+) --> (n\d+|run)""",
    )

    /** A profile of `chain` with fixed numbers, so its rendering can be golden: step 2 took most of the time. */
    private val chainProfile = Profile(
        mapOf(
            0 to StageProfile(elements = 100_000, waitingMillis = 0.004),
            1 to StageProfile(elements = 100_000, busyMillis = 0.001, waitingMillis = 0.003, busySampledMillis = 1.5),
            2 to StageProfile(elements = 100_000, busyMillis = 0.020, waitingMillis = 0.003, busySampledMillis = 31.0),
            3 to StageProfile(elements = 50_000, busyMillis = 0.001, waitingMillis = 0.003, busySampledMillis = 1.5),
            4 to StageProfile(elements = 50_000, busyMillis = 0.004, busySampledMillis = 3.0),
            5 to StageProfile(elements = 50_000, busyMillis = 0.012, busySampledMillis = 9.0),
        ),
    )

    private fun golden(file: String, actual: String): Expectations {
        val expected = javaClass.getResource("/render/$file")?.readText()
        if (expected?.trimEnd() != actual) {
            File("build/render-actual").apply { mkdirs() }.resolve(file).writeText(actual + "\n")
        }
        return withClue("render/$file; the rendering is in build/render-actual/$file") {
            expected?.trimEnd() shouldBe actual
        }
    }

    init {
        pipelines.forEach { (name, run) ->
            renderings.forEach { (suffix, render) ->
                test("every pipeline renders as its golden file says [$name.$suffix]") { _ ->
                    golden("$name.$suffix", render(run))
                }
            }
        }

        pipelines.forEach { (name, run) ->
            test("every Mermaid rendering is made of lines Mermaid's flowchart grammar reads [$name]") { _ ->
                val lines = run.render(Layout.Mermaid).lines()
                (lines.first() shouldBe "flowchart TD") and
                    withClue("lines outside the grammar") {
                        lines.filterNot { mermaidLine.matches(it) }.shouldBeEmpty()
                    }
            }
        }

        test("a profiled rendering has each stage's numbers, and colours it by its share [chain.profiled.txt]") { _ ->
            golden("chain.profiled.txt", chain.render(profile = chainProfile))
        }
        test("a profiled rendering has each stage's numbers, and colours it by its share [chain.profiled.mmd]") { _ ->
            golden("chain.profiled.mmd", chain.render(Layout.Mermaid, profile = chainProfile))
        }
    }
}
