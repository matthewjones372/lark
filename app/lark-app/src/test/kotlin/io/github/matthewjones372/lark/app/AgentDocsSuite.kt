package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.withClue
import java.io.File

/** What an agent outside this repository reads about lark names every published module and nothing else (spec 0134). */
object AgentDocsSuite : Suite() {
    private fun modules(property: String): Set<String>? = System.getProperty(property)?.split(",")?.toSet()

    private fun page(root: String, path: String): String = File(root, path).readText()

    // The clue is for a build that forgot the property, not for what the test then checks.
    private fun repoRoot(then: (String) -> Expectations): Expectations =
        System.getProperty("lark.app.repoRoot")?.let(then)
            ?: withClue("the build must pass -Dlark.app.repoRoot; see lark-app/build.gradle.kts") {
                null.shouldNotBeNull()
            }

    init {
        test("llms txt names every published module, and nothing else, under Modules") {
            modules("lark.app.publishedModules").shouldNotBeNull { published ->
                repoRoot { root -> modulesIn(page(root, "llms.txt")) shouldBe published }
            }
        }

        test("every published module has a description for its POM") {
            modules("lark.app.publishedModules").shouldNotBeNull { published ->
                modules("lark.app.describedModules").shouldNotBeNull { described ->
                    val undescribed = published - described
                    withClue("moduleDescriptions in the root build.gradle.kts") { undescribed shouldBe emptySet() }
                }
            }
        }

        test("the lark skill names the same modules") {
            modules("lark.app.publishedModules").shouldNotBeNull { published ->
                repoRoot { root -> modulesIn(page(root, "plugins/lark/skills/lark/SKILL.md")) shouldBe published }
            }
        }
    }
}

/** The module names a page lists, one `- \`name\`:` line each under its "## Modules" heading. */
internal fun modulesIn(page: String): Set<String> =
    page.substringAfter("## Modules").substringBefore("\n## ")
        .lines()
        .mapNotNull { line -> Regex("""^- `([a-z0-9-]+)`""").find(line)?.groupValues?.get(1) }
        .toSet()
