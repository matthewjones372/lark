package io.github.matthewjones372.lark.structured

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** lark and the JDK: the module's whole point is that the JDK now has what it needs. */
class NoOtherDependenciesTest {

    private val allowed = listOf("lark", "kotlin-stdlib", "annotations-", "arrow-")

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
