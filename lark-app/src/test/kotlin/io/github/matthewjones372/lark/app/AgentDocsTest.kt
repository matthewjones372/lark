package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** What an agent outside this repository reads about lark names every published module and nothing else (spec 0134). */
class AgentDocsTest {

    private val published: Set<String> =
        System.getProperty("lark.app.publishedModules").shouldNotBeNull().split(",").toSet()

    private fun page(path: String): String {
        val root = System.getProperty("lark.app.repoRoot")
        withClue("the build must pass -Dlark.app.repoRoot; see lark-app/build.gradle.kts") { root.shouldNotBeNull() }
        return File(root, path).readText()
    }

    @Test
    fun `llms txt names every published module, and nothing else, under Modules`() {
        modulesIn(page("llms.txt")) shouldBe published
    }

    @Test
    fun `every published module has a description for its POM`() {
        val described = System.getProperty("lark.app.describedModules").shouldNotBeNull().split(",").toSet()
        withClue("moduleDescriptions in the root build.gradle.kts") { published - described shouldBe emptySet() }
    }

    @Test
    fun `the lark skill names the same modules`() {
        modulesIn(page("plugins/lark/skills/lark/SKILL.md")) shouldBe published
    }
}

/** The module names a page lists, one `- \`name\`:` line each under its "## Modules" heading. */
internal fun modulesIn(page: String): Set<String> =
    page.substringAfter("## Modules").substringBefore("\n## ")
        .lines()
        .mapNotNull { line -> Regex("""^- `([a-z0-9-]+)`""").find(line)?.groupValues?.get(1) }
        .toSet()
