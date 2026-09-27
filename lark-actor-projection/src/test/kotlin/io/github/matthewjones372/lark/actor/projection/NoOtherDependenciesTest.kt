package io.github.matthewjones372.lark.actor.projection

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-actor-projection may put on a service's classpath: lark-actor's and lark-stream's own, and no more. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
    )

    @Test
    fun `the main runtime classpath is lark-actor and lark-stream, and nothing else`() {
        val raw = System.getProperty("lark.actor.projection.runtimeClasspath")
        withClue("the build must pass -Dlark.actor.projection.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-actor-projection must stay lark-actor and lark-stream, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
