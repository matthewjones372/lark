package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What lark-stream-test is allowed to put on a consumer's classpath, stated as a test: lark-stream-forks
 * and what it brings, which is lark-stream, lark, Arrow and the Kotlin standard library.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        Regex("""arrow-.*"""),
        Regex("""lark(-\d.*)?\.jar"""),
        Regex("""lark-stream(-\d.*)?\.jar"""),
        Regex("""lark-stream-forks(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is lark-stream-forks and what it brings, and nothing else`() {
        val raw = System.getProperty("lark.stream.runtimeClasspath")
        withClue("the build must pass -Dlark.stream.runtimeClasspath; see lark-stream-test/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("lark-stream-test must stay lark-stream-forks and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
