package io.github.matthewjones372.lark.slf4j

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: lark, and the facade alone. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        // The facade. No backend: a service brings the one it has already configured, and two on a
        // classpath is a warning at start-up and an arbitrary winner.
        "slf4j-api",
    )

    @Test
    fun `the main runtime classpath is lark and the SLF4J facade, and nothing else`() {
        val raw = System.getProperty("lark.slf4j.runtimeClasspath")
        withClue("the build must pass -Dlark.slf4j.runtimeClasspath; see lark-slf4j/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-slf4j must stay lark plus the SLF4J facade, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
