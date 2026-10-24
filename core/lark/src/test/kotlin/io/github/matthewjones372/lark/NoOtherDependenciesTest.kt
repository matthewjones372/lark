package io.github.matthewjones372.lark

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test — the claim `AGENTS.md` makes for it: the JDK and the Kotlin standard library, and nothing
 * else. No JSON library, no HTTP library, no coroutines.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-")

    @Test
    fun `the main runtime classpath is the standard library, and nothing else`() {
        val raw = System.getProperty("lark.runtimeClasspath")
        withClue("the build must pass -Dlark.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark must stay the JDK and the standard library, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
