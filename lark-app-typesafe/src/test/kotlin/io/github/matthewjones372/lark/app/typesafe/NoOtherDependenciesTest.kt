package io.github.matthewjones372.lark.app.typesafe

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "arrow-", "lark", "config-")

    @Test
    fun `the main runtime classpath is lark-app and Typesafe Config, and nothing else`() {
        val raw = System.getProperty("lark.app.typesafe.runtimeClasspath")
        withClue("the build must pass -Dlark.app.typesafe.runtimeClasspath; see the build file") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-app-typesafe must stay lark-app plus config, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
