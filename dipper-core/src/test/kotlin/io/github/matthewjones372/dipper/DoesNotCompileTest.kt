package io.github.matthewjones372.dipper

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The two lines the types are asked to hold, compiled: an element is never
 * null, and a declared failure stays in the type until something handles it.
 *
 * A claim about what does not compile is worth nothing written down — the
 * signature it depends on can change under it and nothing notices. So the
 * fixtures below are compiled for real and the compiler's own words asserted.
 */
class DoesNotCompileTest {

    @TempDir
    lateinit var workspace: File

    /** As [compile], for a fixture that is expected to be accepted. */
    private fun compiles(source: String): List<String> = build(source).second

    private fun compile(source: String): List<String> {
        val (exit, errors) = build(source)
        withClue("the fixture compiled, and this test exists because it must not") {
            exit shouldNotBe ExitCode.OK
        }
        return errors
    }

    private fun build(source: String): Pair<ExitCode, List<String>> {
        val file = File(workspace, "Fixture.kt").apply { writeText(source) }
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() = errors.clear()
            override fun hasErrors() = errors.isNotEmpty()
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) {
                if (severity.isError) errors += message
            }
        }

        val exit = K2JVMCompiler().exec(
            collector,
            Services.EMPTY,
            K2JVMCompilerArguments().apply {
                freeArgs = listOf(file.absolutePath)
                classpath = System.getProperty("java.class.path")
                destination = File(workspace, "out").absolutePath
                noStdlib = true
                noReflect = true
                // The library on the classpath is built for 21; without this
                // the compiler defaults to 1.8 and every fixture fails for a
                // reason that has nothing to do with what is being asserted.
                jvmTarget = "21"
            },
        )

        return exit to errors
    }

    private val preamble = """
        import io.github.matthewjones372.dipper.Stream
        import io.github.matthewjones372.dipper.from
        import io.github.matthewjones372.dipper.map
        import io.github.matthewjones372.dipper.mapOrFail
        import io.github.matthewjones372.dipper.toSource

        data class Row(val id: Int, val customer: String?)
        data class NoCustomer(val id: Int)

        val rows = listOf(Row(1, "ada"))
    """.trimIndent()

    @Test
    fun `mapping to a nullable field is refused by the element bound`() {
        val errors = compile("$preamble\nval broken = Stream.from(rows).map { row -> row.customer }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "Return type mismatch: expected 'Any', actual 'String?'."
        }
    }

    @Test
    fun `a mapOrFail body that can answer null is refused by the same bound`() {
        val errors = compile("$preamble\nval broken = Stream.from(rows).mapOrFail { row -> row.customer }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "Return type mismatch: expected 'Any', actual 'String?'."
        }
    }

    @Test
    fun `a stream still carrying a failure has no toSource to call`() {
        val errors = compile(
            """
            $preamble
            val failing = Stream.from(rows).mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) }
            val escaped = failing.toSource()
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "toSource"
        }
    }

    /** The other side of the claim: handle the null and the way out opens. */
    @Test
    fun `a stream with nothing left to fail with reaches toSource`() {
        val errors = compiles(
            """
            $preamble
            val handled = Stream.from(rows).map { row -> row.customer ?: "unknown" }
            val out = handled.toSource()
            """.trimIndent(),
        )

        withClue("if this ever stops compiling, the way out to Pekko is shut") {
            errors.shouldBeEmpty()
        }
    }
}
