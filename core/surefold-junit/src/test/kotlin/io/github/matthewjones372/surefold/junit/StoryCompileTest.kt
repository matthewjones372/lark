package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeEmpty
import io.github.matthewjones372.surefold.assert.shouldContain
import io.github.matthewjones372.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which `story` a suite calls, under the compiler options this module builds with. */
class StoryCompileTest {

    private fun suiteOf(body: String, vararg imports: String) = """
        import io.github.matthewjones372.surefold.Resource
        import io.github.matthewjones372.surefold.junit.SharedSuite
        import io.github.matthewjones372.surefold.junit.shouldBe
        import io.github.matthewjones372.surefold.resource
        ${imports.joinToString("\n") { "import $it" }}

        object Fixture : SharedSuite<Int>() {
            override val shared: Resource<Int> = resource { 1 }

            init {
                test("the fixture") { one -> $body }
            }
        }
    """.trimIndent()

    private val steps = """
        story {
            Given("one") { }
            Then("it is one") { one shouldBe 1 } and Then("it is not two") { one shouldBe 1 }
        }
    """.trimIndent()

    @Test
    fun `a suite importing surefold-junit's story returns the story's expectations`(@TempDir workspace: File) {
        val source = suiteOf(steps, "io.github.matthewjones372.surefold.junit.story")
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        withClue(reported.joinToString("\n")) { exit shouldBe ExitCode.OK }
        reported.shouldBeEmpty()
    }

    @Test
    fun `a suite importing surefold-test's story alone does not compile, since that story returns Unit`(
        @TempDir workspace: File,
    ) {
        val (exit, _) =
            EmbeddedKotlin(workspace).compile(suiteOf(steps, "io.github.matthewjones372.surefold.test.story"))

        exit shouldBe ExitCode.COMPILATION_ERROR
    }

    @Test
    fun `a file importing both stories calls neither, until one is imported under another name`(
        @TempDir workspace: File,
    ) {
        val both = arrayOf(
            "io.github.matthewjones372.surefold.junit.story",
            "io.github.matthewjones372.surefold.test.story",
        )
        val (ambiguous, reported) = EmbeddedKotlin(workspace).compile(suiteOf(steps, *both))

        ambiguous shouldBe ExitCode.COMPILATION_ERROR
        reported.joinToString("\n") shouldContain "ambiguity"

        val renamed = suiteOf(steps, both[0], "${both[1]} as jupiterStory")
        val (exit, said) = EmbeddedKotlin(workspace).compile(renamed)
        withClue(said.joinToString("\n")) { exit shouldBe ExitCode.OK }
    }

    @Test
    fun `a dropped story does not compile`(@TempDir workspace: File) {
        val source = suiteOf("$steps\n one shouldBe 1", "io.github.matthewjones372.surefold.junit.story")
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        exit shouldBe ExitCode.COMPILATION_ERROR
        reported shouldContain "Unused return value of 'story'."
    }

    // Spec 0188's second question: Story's generic Then is a member, so it wins over any Then surefold-junit adds.
    @Test
    fun `a dropped then still compiles`(@TempDir workspace: File) {
        val source = suiteOf(
            "story { Then(\"it is two\") { one shouldBe 2 }\n Then(\"it is one\") { one shouldBe 1 } }",
            "io.github.matthewjones372.surefold.junit.story",
        )
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        withClue(reported.joinToString("\n")) { exit shouldBe ExitCode.OK }
    }
}
