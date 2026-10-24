package io.github.matthewjones372.lark.app.kotlinx

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed =
        listOf("kotlin-stdlib", "annotations-", "lark", "config-", "kotlinx-serialization-")

    @Test
    fun `the main runtime classpath is lark-app-typesafe and kotlinx serialization, and nothing else`() {
        val raw = System.getProperty("lark.app.kotlinx.runtimeClasspath")
        withClue("the build must pass -Dlark.app.kotlinx.runtimeClasspath; see the build file") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-app-kotlinx must stay lark-app-typesafe plus kotlinx serialization, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
