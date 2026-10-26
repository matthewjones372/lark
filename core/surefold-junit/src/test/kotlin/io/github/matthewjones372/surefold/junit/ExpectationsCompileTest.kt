package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeEmpty
import io.github.matthewjones372.surefold.assert.shouldContain
import io.github.matthewjones372.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A test that checks nothing does not compile, under the compiler options this module builds with. */
class ExpectationsCompileTest {

    private fun suiteOf(body: String, vararg imports: String) = """
        import io.github.matthewjones372.surefold.Resource
        import io.github.matthewjones372.surefold.junit.SharedSuite
        import io.github.matthewjones372.surefold.junit.expect
        import io.github.matthewjones372.surefold.resource
        ${imports.joinToString("\n") { "import $it" }}

        object Fixture : SharedSuite<Int>() {
            override val shared: Resource<Int> = resource { 1 }

            init {
                test("the fixture") { one -> $body }
            }
        }
    """.trimIndent()

    @Test
    fun `a body that returns its expectations compiles`(@TempDir workspace: File) {
        val (exit, reported) = EmbeddedKotlin(workspace).compile(suiteOf("expect(one == 1) and expect(one > 0)"))

        withClue(reported.joinToString("\n")) { exit shouldBe ExitCode.OK }
        reported.shouldBeEmpty()
    }

    @Test
    fun `a body that returns Unit does not compile`(@TempDir workspace: File) {
        val (exit, reported) = EmbeddedKotlin(workspace).compile(suiteOf("println(one)"))

        exit shouldBe ExitCode.COMPILATION_ERROR
        reported.joinToString("\n") shouldContain "Expectations"
    }

    @Test
    fun `a dropped expect does not compile`(@TempDir workspace: File) {
        val (exit, reported) = EmbeddedKotlin(workspace).compile(suiteOf("expect(one == 2); expect(true)"))

        exit shouldBe ExitCode.COMPILATION_ERROR
        reported shouldContain "Unused return value of 'expect'."
    }

    @Test
    fun `a dropped and does not compile`(@TempDir workspace: File) {
        val source = suiteOf("expect(one == 2) and expect(true); expect(true)")
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        exit shouldBe ExitCode.COMPILATION_ERROR
        reported shouldContain "Unused return value of 'and'."
    }

    @Test
    fun `a dropped shouldBe does not compile`(@TempDir workspace: File) {
        val source = suiteOf("one shouldBe 2; expect(true)", "io.github.matthewjones372.surefold.junit.shouldBe")
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        exit shouldBe ExitCode.COMPILATION_ERROR
        reported shouldContain "Unused return value of 'shouldBe'."
    }

    @Test
    fun `a file importing both shouldBe's calls neither, until one is imported under another name`(
        @TempDir workspace: File,
    ) {
        val both =
            arrayOf(
                "io.github.matthewjones372.surefold.junit.shouldBe",
                "io.github.matthewjones372.surefold.assert.shouldBe",
            )
        val (ambiguous, reported) = EmbeddedKotlin(workspace).compile(suiteOf("one shouldBe 1", *both))

        ambiguous shouldBe ExitCode.COMPILATION_ERROR
        reported.joinToString("\n") shouldContain "ambiguity"

        val renamed = suiteOf("one shouldEqual 1; one shouldBe 1", both[0], "${both[1]} as shouldEqual")
        EmbeddedKotlin(workspace).compile(renamed).first shouldBe ExitCode.OK
    }
}
