package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.reflect.typeOf

private class Hammer
private class Nail

/** Where this file's own sources are, which is what a build hands the checker. */
private val sources = listOf(File("src/test/kotlin"))

class DiagnosticsTest {

    @Test
    fun `a missing dependency is a compiler error pointing at the line that asked`() {
        val line = single { _: Hammer -> Nail() }.findings().diagnostics(sources)

        line shouldStartWith "e: file://"
        line shouldContain "DiagnosticsTest.kt:"
        line shouldContain "Nail needs Hammer, and nothing builds it"
        withClue("a diagnostic that wraps is two diagnostics to whatever parses the output") {
            line.lines().size shouldBe 1
        }
    }

    @Test
    fun `the file in the URI is one that exists, so the IDE has something to open`() {
        val line = single { _: Hammer -> Nail() }.findings().diagnostics(sources)

        // `file://<path>:<line>:<column> <message>`, and the message has colons of its own.
        val located = line.substringAfter("file://").substringBefore(" ")
        val path = located.substringBeforeLast(":").substringBeforeLast(":")

        File(path).isFile shouldBe true
    }

    @Test
    fun `a fault with no line of its own still gets a diagnostic`() {
        val loop = single { _: Nail -> Hammer() } + single { _: Hammer -> Nail() }

        val line = loop.findings().diagnostics(sources)

        withClue("a cycle is written on as many lines as it has nodes, so it names none of them") {
            line shouldStartWith "e: lark-app: cycle "
        }
    }

    @Test
    fun `an unreached node is a warning rather than an error`() {
        val module = singleOf(::Hammer) + singleOf(::Nail)

        val line = module.findings(typeOf<Nail>()).diagnostics(sources)

        line shouldStartWith "w: file://"
        line shouldContain "nothing reaches Hammer"
    }

    @Test
    fun `a key the graph builds under a name that reads the same is named beside the missing one`() {
        val fault = Finding(Severity.FAIL, WiringError.Missing(typeOf<Hammer>(), typeOf<Nail>()))

        val line = listOf(fault).diagnostics(sources, setOf(typeOf<Hammer?>()))

        withClue("nothing builds Hammer, with a Hammer? in the graph, is the least helpful true thing") {
            line shouldContain "which is not the same type"
        }
    }

    @Test
    fun `without source directories there is nothing to link and the sentence still says it`() {
        val line = single { _: Hammer -> Nail() }.findings().diagnostics(emptyList())

        line shouldBe "e: lark-app: Nail needs Hammer, and nothing builds it"
    }
}
