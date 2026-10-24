package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What the fence-compiling tests do not check: the order of an example's imports, and the coordinates it names. */
class DocsTest {

    private val root = File(System.getProperty("lark.app.repoRoot").shouldNotBeNull())

    private val pages: List<File> =
        listOf(File(root, "README.md")) +
            File(root, "docs").listFiles { file -> file.extension == "md" }.shouldNotBeNull().sortedBy { it.name }

    private val published: Set<String> =
        System.getProperty("lark.app.publishedModules").shouldNotBeNull().split(",").toSet()

    @Test
    fun `every import block in a Kotlin fence is in ktlint's order`() {
        val unordered = pages.flatMap { page ->
            importBlocks(page.readText()).filter { it != it.sortedWith(ktlintImportOrder) }
                .map { "${page.name}: ${it.first()}" }
        }
        unordered shouldBe emptyList()
    }

    @Test
    fun `every coordinate names a published module, at the released version or a snapshot`() {
        val released = releasedVersion(File(root, "docs/modules.md").readText())
        val wrong = pages.flatMap { page ->
            COORDINATE.findAll(page.readText()).map { it.groupValues }
                .filterNot { (_, module, version) ->
                    module in published && (version == released || version.endsWith("-SNAPSHOT"))
                }
                .map { "${page.name}: ${it[0]}" }
        }
        withClue("released version $released, as docs/modules.md says") { wrong shouldBe emptyList() }
    }
}

private val COORDINATE = Regex("""io\.github\.matthewjones372:([A-Za-z0-9._-]+):([A-Za-z0-9._-]+)""")

/** The version docs/modules.md installs, which its snapshot paragraph must name as the last tag too. */
internal fun releasedVersion(modules: String): String {
    val installed = COORDINATE.findAll(modules).map { it.groupValues[2] }.toSet().single()
    val tagged = Regex("""after `v([^`]+)`""").find(modules).shouldNotBeNull().groupValues[1]
    withClue("docs/modules.md: the install block and the snapshot paragraph") { tagged shouldBe installed }
    return installed
}

/** Each run of consecutive `import` lines inside a ```kotlin fence. */
internal fun importBlocks(page: String): List<List<String>> =
    Regex("""```kotlin\n(.*?)```""", RegexOption.DOT_MATCHES_ALL).findAll(page).flatMap { fence ->
        fence.groupValues[1].lines().fold(listOf(emptyList<String>())) { blocks, line ->
            when {
                line.startsWith("import ") -> blocks.dropLast(1) + listOf(blocks.last() + line)
                blocks.last().isEmpty() -> blocks
                else -> blocks + listOf(emptyList())
            }
        }.filter { it.isNotEmpty() }
    }.toList()

/** ktlint's `intellij_idea` layout, `*,java.**,javax.**,kotlin.**,^`: each group in plain string order. */
internal val ktlintImportOrder: Comparator<String> = compareBy<String> { line ->
    val path = line.removePrefix("import ")
    when {
        " as " in path -> 4
        path.startsWith("java.") -> 1
        path.startsWith("javax.") -> 2
        path.startsWith("kotlin.") -> 3
        else -> 0
    }
}.thenBy { it.removePrefix("import ").substringBefore(" as ").replace("`", "") }
