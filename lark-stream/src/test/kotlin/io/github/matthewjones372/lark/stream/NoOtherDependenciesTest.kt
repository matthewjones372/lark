package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What lark-stream is allowed to put on a consumer's classpath, stated as a test: the Kotlin standard
 * library, lark, and the Arrow that arrives with it. No Pekko, no lark-pekko: a description names no
 * backend, and a service picks one by depending on it.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        Regex("""arrow-.*"""),
        // lark itself, and only lark: `lark-pekko` and every other lark module start with the same word.
        Regex("""lark(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is lark and arrow, and nothing else`() {
        val raw = System.getProperty("lark.stream.runtimeClasspath")
        withClue("the build must pass -Dlark.stream.runtimeClasspath; see lark-stream/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("lark-stream must stay lark and arrow, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
