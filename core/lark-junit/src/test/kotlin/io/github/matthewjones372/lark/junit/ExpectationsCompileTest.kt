package io.github.matthewjones372.lark.junit

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A test that checks nothing does not compile, under the compiler options this module builds with. */
class ExpectationsCompileTest {

    private fun suiteOf(body: String) = """
        import io.github.matthewjones372.lark.Resource
        import io.github.matthewjones372.lark.junit.Suite
        import io.github.matthewjones372.lark.junit.expect
        import io.github.matthewjones372.lark.resource

        object Fixture : Suite<Int>() {
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
}
