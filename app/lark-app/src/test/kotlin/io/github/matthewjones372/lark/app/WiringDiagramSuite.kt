package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldNotBe
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.withClue
import java.io.File
import java.security.MessageDigest

private class Tuning
private class Database
private class Memo
private class Accounts
private class Postings
private class Opening
private class Reporting
private class Frontage

private val fenced = Regex("""<!-- cookbook-diagram -->\s*```mermaid\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

/** Runs [check] against the repository's root, or fails saying the build did not pass it. */
private inline fun withRepo(check: (File) -> Expectations): Expectations {
    val root: String? = System.getProperty("lark.app.repoRoot")
    return if (root == null) {
        withClue("the build must pass -Dlark.app.repoRoot; see lark-app/build.gradle.kts") { root.shouldNotBeNull() }
    } else {
        check(File(root))
    }
}

/**
 * The diagram the cookbook prints, held to what `render` actually answers. A drawing of a graph is
 * worth having in review only while it is the graph.
 */
object WiringDiagramSuite : Suite() {
    private val app: Module =
        single<Tuning> { Tuning() } +
            single { _: Tuning -> Database() } +
            single { _: Tuning -> Memo() } +
            single { _: Database -> Accounts() } +
            single { _: Database -> Postings() } +
            single { _: Accounts -> Opening() } +
            single { _: Accounts, _: Postings -> Reporting() } +
            single { _: Memo, _: Opening, _: Reporting -> Frontage() }

    init {
        test("the cookbook prints the diagram this graph renders") {
            withRepo { repo ->
                val cookbook = File(repo, "docs/cookbook.md").readText()
                val matches = fenced.findAll(cookbook).map { it.groupValues[1] }.toList()
                if (matches.size != 1) {
                    withClue("docs/cookbook.md must hold one mermaid fence marked <!-- cookbook-diagram -->") {
                        matches.size shouldBe 1
                    }
                } else {
                    app.render() shouldBe matches.single()
                }
            }
        }
    }
}

/**
 * The PNG beside the fence, held to the fence it was drawn from.
 *
 * The picture cannot be re-rendered here — that needs a browser, and CI has no business drawing
 * pictures — so what is checked is the recording of what it was drawn from. A graph that changes
 * fails this until `docs/render-diagram.sh` has been run.
 */
object DiagramImageSuite : Suite() {
    private fun drawnFence(repo: File): String =
        fenced.find(File(repo, "docs/cookbook.md").readText())!!.groupValues[1] + "\n"

    init {
        test("the png is drawn from the fence the cookbook prints") {
            withRepo { repo ->
                val recorded = File(repo, "docs/wiring.png.sha256")
                if (!recorded.exists()) {
                    withClue("docs/wiring.png.sha256 is written by docs/render-diagram.sh") {
                        recorded.exists() shouldBe true
                    }
                } else {
                    val hashed = MessageDigest.getInstance("SHA-256")
                        .digest(drawnFence(repo).toByteArray())
                        .joinToString("") { "%02x".format(it) }

                    withClue("the graph moved and the picture did not: run docs/render-diagram.sh") {
                        recorded.readText().trim() shouldBe hashed
                    }
                }
            }
        }

        test("the picture is there to be linked") {
            withRepo { repo -> File(repo, "docs/wiring.png").length() shouldNotBe 0L }
        }
    }
}
