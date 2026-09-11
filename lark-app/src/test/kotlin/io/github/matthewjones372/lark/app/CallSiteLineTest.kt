package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

private class Widget
private class Cog

/**
 * The line numbers below are load-bearing: `single` is inline, so the line the JVM reports for the
 * frame is a synthetic one past the end of this file, and the whole point of reading the SMAP is
 * that a site names a line somebody can open. Adding a line above one of these breaks the test,
 * which is the test working.
 */
class CallSiteLineTest {

    private val declaredOnLine = 22

    private val module = single { _: Widget -> Cog() }

    @Test
    fun `a site names the line the recipe was written on, not the line the JVM reports`() {
        val site = module.nodes.values.single().site.shouldNotBeNull()

        site shouldBe "io/github/matthewjones372/lark/app/CallSiteLineTest.kt:$declaredOnLine"
    }

    @Test
    fun `a line past the end of the file would be one nobody could open`() {
        val line = module.nodes.values.single().site.shouldNotBeNull().substringAfterLast(':').toInt()

        withClue("this file is shorter than the synthetic lines the inliner emits") {
            (line <= lines()) shouldBe true
        }
    }

    // The repository root, which the build already hands every test in this module.
    private fun lines(): Int =
        File(System.getProperty("lark.app.repoRoot"), "lark-app/src/test/kotlin/$SOURCE").readLines().size
}

private const val SOURCE = "io/github/matthewjones372/lark/app/CallSiteLineTest.kt"
