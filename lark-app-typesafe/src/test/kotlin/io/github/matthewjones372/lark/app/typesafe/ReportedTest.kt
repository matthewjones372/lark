package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
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

    private fun printed(marker: String = "cookbook-config-report"): String {
        val fenced = Regex("""<!-- $marker -->\s*```\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
        val found = fenced.findAll(cookbook().readText()).map { it.groupValues[1] }.toList()
        withClue("docs/cookbook.md must carry one fence marked <!-- $marker -->") {
            found.size shouldBe 1
        }
        return found.single()
    }

    @Test
    fun `the cookbook prints the report this stack answers`() {
        stacked.origins().report() shouldBe printed()
    }

    @Test
    fun `the cookbook prints the block a section logs`() {
        val read = stacked.config.sectionOf("petshop") {
            int("port") to section("db") { int("pool") to secret("password") }
        }

        stacked.config.reportOf("petshop", read.paths) shouldBe printed("cookbook-section-report")
    }
}
