package io.github.matthewjones372.lark.cluster

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The examples in `docs/cluster.md`, compiled out of the page (spec 0084): each fence marked `<!-- cluster-… -->`,
 * a marker that renders as nothing and survives the heading above it being reworded.
 */
class GuideExampleTest {

    private val markers = listOf("remote", "tls", "membership", "entities").map { "<!-- cluster-$it -->" }

    @TempDir
    lateinit var workspace: File

    private fun guide(): String {
        val root = System.getProperty("lark.cluster.repoRoot")
        withClue("the build must pass -Dlark.cluster.repoRoot; see lark-cluster/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, "docs/cluster.md").readText()
    }

    private fun fences(marker: String): List<String> =
        Regex("""$marker\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(guide()).map { it.groupValues[1] }.toList()

    private fun only(marker: String): String {
        withClue("docs/cluster.md must hold one kotlin fence marked $marker") { fences(marker).size shouldBe 1 }
        return fences(marker).single()
    }

    private fun errors(source: String): List<String> {
        val (exit, errors) = EmbeddedKotlin(workspace).compile(source)
        return errors + listOfNotNull(if (exit == ExitCode.OK) null else "exit $exit")
    }

    @Test
    fun `every marked example on the page compiles against the library`() {
        markers.forEach { marker ->
            withClue(marker) { errors(only(marker)).shouldBeEmpty() }
        }
    }

    @Test
    fun `an example that no longer matches the library fails to compile`() {
        errors(only(markers.first()).replace("node.expose(", "node.publish(")).shouldNotBeEmpty()
    }
}
