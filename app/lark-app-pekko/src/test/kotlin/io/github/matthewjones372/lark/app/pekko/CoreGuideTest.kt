package io.github.matthewjones372.lark.app.pekko

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The complete examples in `docs/core.md`, `docs/observability.md`, `docs/testing.md` and the README's suite, each
 * compiled on its own: this module's test classpath holds the core, lark-pekko and lark-junit, which is all they use.
 */
class CoreGuideTest {

    private val pages =
        listOf(
            "docs/core.md" to "core",
            "docs/observability.md" to "observability",
            "docs/testing.md" to "testing",
            "README.md" to "readme",
        )

    @TempDir
    lateinit var workspace: File

    private fun page(path: String): String {
        val root = System.getProperty("lark.app.pekko.repoRoot")
        withClue("the build must pass -Dlark.app.pekko.repoRoot; see lark-app-pekko/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, path).readText()
    }

    /** Every fence marked `<!-- prefix-name -->` on the page, by its marker. */
    private fun fences(path: String, prefix: String): Map<String, String> =
        Regex("""<!-- ($prefix-[a-z-]+) -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page(path)).associate { it.groupValues[1] to it.groupValues[2] }

    private fun errors(source: String): List<String> {
        val (exit, errors) = EmbeddedKotlin(File(workspace, "${source.hashCode()}").apply { mkdirs() }).compile(source)
        return errors + listOfNotNull(if (exit == ExitCode.OK) null else "exit $exit")
    }

    @Test
    fun `every marked example in the guides compiles against the library`() {
        pages.forEach { (path, prefix) ->
            val marked = fences(path, prefix)
            withClue("$path must carry examples marked <!-- $prefix-… -->, or this test asserts nothing") {
                marked.keys.shouldNotBeEmpty()
            }
            marked.forEach { (marker, source) -> withClue("$path $marker") { errors(source).shouldBeEmpty() } }
        }
    }

    @Test
    fun `the testing guide tells a story in a suite`() {
        fences("docs/testing.md", "testing").keys shouldContain "testing-junit-story"
    }

    @Test
    fun `an example that no longer matches the library fails to compile`() {
        val forking = fences("docs/core.md", "core").getValue("core-forking")
        errors(forking.replace("parZip(", "parZipped(")).shouldNotBeEmpty()
    }

    @Test
    fun `every marker on a page is unique`() {
        pages.forEach { (path, prefix) ->
            val markers = Regex("""<!-- ($prefix-[a-z-]+) -->""").findAll(page(path)).map { it.groupValues[1] }.toList()
            withClue(path) { markers.size shouldBe markers.toSet().size }
        }
    }
}
