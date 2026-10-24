package io.github.matthewjones372.lark.structured

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** lark and the JDK: the module's whole point is that the JDK now has what it needs. */
class NoOtherDependenciesTest {

    private val allowed = listOf("lark", "kotlin-stdlib", "annotations-")

    @Test
    fun `the main runtime classpath is lark, and nothing else`() {
        val raw = System.getProperty("lark.structured.runtimeClasspath")
        withClue("the build must pass -Dlark.structured.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-structured must stay lark and the JDK, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
