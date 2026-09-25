package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What lark-stream-forks is allowed to put on a consumer's classpath, stated as a test: lark-stream and
 * what it brings, which is lark, Arrow and the Kotlin standard library. A run on forks needs nothing else.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        Regex("""arrow-.*"""),
        Regex("""lark(-\d.*)?\.jar"""),
        Regex("""lark-stream(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is lark-stream, lark and arrow, and nothing else`() {
        val raw = System.getProperty("lark.stream.runtimeClasspath")
        withClue("the build must pass -Dlark.stream.runtimeClasspath; see lark-stream-forks/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("lark-stream-forks must stay lark-stream, lark and arrow, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
