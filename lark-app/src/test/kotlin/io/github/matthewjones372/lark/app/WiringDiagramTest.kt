package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

private class Tuning
private class Database
private class Memo
private class Accounts
private class Postings
private class Opening
private class Reporting
private class Frontage

/**
 * The diagram the cookbook prints, held to what `render` actually answers. A drawing of a graph is
 * worth having in review only while it is the graph.
 */
class WiringDiagramTest {

    private val app: Module =
        single<Tuning> { Tuning() } +
            single { _: Tuning -> Database() } +
            single { _: Tuning -> Memo() } +
            single { _: Database -> Accounts() } +
            single { _: Database -> Postings() } +
            single { _: Accounts -> Opening() } +
            single { _: Accounts, _: Postings -> Reporting() } +
            single { _: Memo, _: Opening, _: Reporting -> Frontage() }

    private fun cookbook(): File {
        val root = System.getProperty("lark.app.repoRoot")
        withClue("the build must pass -Dlark.app.repoRoot; see lark-app/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, "docs/cookbook.md")
    }

    private fun drawn(): String {
        val fenced = Regex("""<!-- cookbook-diagram -->\s*```mermaid\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
        val matches = fenced.findAll(cookbook().readText()).map { it.groupValues[1] }.toList()
        withClue("docs/cookbook.md must hold one mermaid fence marked <!-- cookbook-diagram -->") {
            matches.size shouldBe 1
        }
        return matches.single()
    }

    @Test
    fun `the cookbook prints the diagram this graph renders`() {
        app.render() shouldBe drawn()
    }
}
