package io.github.matthewjones372.lark.test

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-test may put on a consumer's classpath: lark and what it brings, and no assertion library. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        Regex("""lark(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is lark and what it brings, and nothing else`() {
        val raw = System.getProperty("lark.test.runtimeClasspath")
        withClue("the build must pass -Dlark.test.runtimeClasspath; see lark-test/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("lark-test must stay lark and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
