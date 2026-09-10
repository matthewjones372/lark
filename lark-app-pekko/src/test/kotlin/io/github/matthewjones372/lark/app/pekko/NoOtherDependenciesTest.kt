package io.github.matthewjones372.lark.app.pekko

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        "pekko-actor",
        "scala-library",
        "config-",
        // What pekko-actor-typed brings: typed Pekko logs through slf4j rather than the classic event
        // bus, so the facade and Pekko's bridge to it arrive with the artifact and are not declared here.
        "pekko-slf4j",
        "slf4j-api",
    )

    @Test
    fun `the main runtime classpath is lark-app and pekko-actor-typed, and nothing else`() {
        val raw = System.getProperty("lark.app.pekko.runtimeClasspath")
        withClue("the build must pass -Dlark.app.pekko.runtimeClasspath; see lark-app-pekko/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-app-pekko must stay lark-app plus pekko-actor-typed, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
