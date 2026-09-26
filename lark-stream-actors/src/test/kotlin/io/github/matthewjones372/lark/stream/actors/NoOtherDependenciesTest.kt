package io.github.matthewjones372.lark.stream.actors

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What lark-stream-actors is allowed to put on a consumer's classpath, stated as a test: lark-stream-forks and
 * lark-actor, and what they bring, which is lark-stream, lark, Arrow and the Kotlin standard library.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        Regex("""arrow-.*"""),
        Regex("""lark(-\d.*)?\.jar"""),
        Regex("""lark-stream(-\d.*)?\.jar"""),
        Regex("""lark-stream-forks(-\d.*)?\.jar"""),
        Regex("""lark-actor(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is lark-stream-forks, lark-actor, what they bring, and nothing else`() {
        val raw = System.getProperty("lark.stream.actors.runtimeClasspath")
        withClue("the build must pass -Dlark.stream.actors.runtimeClasspath; see lark-stream-actors/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("lark-stream-actors must stay lark-stream-forks and lark-actor, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
