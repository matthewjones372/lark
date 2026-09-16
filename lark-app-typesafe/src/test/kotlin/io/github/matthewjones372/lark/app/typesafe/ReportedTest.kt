package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The report the cookbook prints, held to what the code answers. A sample of an output is worth
 * having in a page only while it is the output.
 */
class ReportedTest {

    private val stacked = layeredConfigOf(
        "application.conf" to ConfigFactory.parseResources("demo/application.conf"),
        "reference.conf" to ConfigFactory.parseResources("demo/reference.conf"),
    )

    private fun cookbook(): File {
        val root = System.getProperty("lark.app.typesafe.repoRoot")
        withClue("the build must pass -Dlark.app.typesafe.repoRoot; see lark-app-typesafe/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, "docs/cookbook.md")
    }

    private fun printed(): String {
        val fenced = Regex("""<!-- cookbook-config-report -->\s*```\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
        val found = fenced.findAll(cookbook().readText()).map { it.groupValues[1] }.toList()
        withClue("docs/cookbook.md must carry one fence marked <!-- cookbook-config-report -->") {
            found.size shouldBe 1
        }
        return found.single()
    }

    @Test
    fun `the cookbook prints the report this stack answers`() {
        stacked.origins().report() shouldBe printed()
    }
}
