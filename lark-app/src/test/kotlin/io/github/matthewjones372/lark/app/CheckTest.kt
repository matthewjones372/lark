package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Run against this module's own test output, so discovery is the real thing: `Sampled` and `Faulty`
 * in `LarkAppTest.kt` are the applications it finds.
 */
class CheckTest {

    private fun testClasses(): List<File> {
        val dirs = System.getProperty("lark.app.testClasses")
        withClue("the build must pass -Dlark.app.testClasses; see lark-app/build.gradle.kts") {
            dirs.shouldNotBeNull()
        }
        return dirs!!.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
    }

    @Test
    fun `an application declared as an object is found by its supertype`() {
        val found = apps(testClasses()).map { it::class.simpleName }

        found shouldContain "Sampled"
        found shouldContain "Faulty"
    }

    @Test
    fun `a graph short of a key fails the check and names the application`() {
        val checked = check(testClasses())

        checked.failed shouldBe true
        checked.report shouldContain "Faulty"
        checked.report shouldContain "missing File"
    }

    @Test
    fun `a diagram is written for every application found`(@TempDir into: File) {
        check(testClasses(), diagrams = into)

        File(into, "Sampled.mmd").readText() shouldContain "graph TD"
    }

    @Test
    fun `a directory with no application passes and says nothing`(@TempDir empty: File) {
        val checked = check(listOf(empty))

        checked.failed shouldBe false
        checked.report shouldBe ""
    }

    @Test
    fun `a warning stops the build only where the caller asked it to`(@TempDir only: File) {
        alone("Warned", only)

        withClue("Warned provides one key twice, which the default floor lets through") {
            check(listOf(only)).failed shouldBe false
        }
        check(listOf(only), failOn = Severity.WARN).failed shouldBe true
    }

    /**
     * One class file copied into [into], so the check sees one application. Only the scan reads this
     * directory; loading goes through the classloader that already holds the test classpath.
     */
    private fun alone(name: String, into: File) {
        val path = "io/github/matthewjones372/lark/app/$name.class"
        val source = testClasses().map { File(it, path) }.first { it.exists() }
        val target = File(into, path)
        target.parentFile.mkdirs()
        source.copyTo(target)
    }
}
