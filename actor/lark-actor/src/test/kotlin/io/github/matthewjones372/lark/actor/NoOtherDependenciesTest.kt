package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-actor may put on a consumer's classpath: the Kotlin standard library and lark. No actor system. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        // lark itself, and only lark: every other lark module starts with the same word.
        Regex("""lark(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is lark, and nothing else`() {
        val raw = System.getProperty("lark.actor.runtimeClasspath")
        withClue("the build must pass -Dlark.actor.runtimeClasspath; see lark-actor/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("lark-actor must stay lark, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
