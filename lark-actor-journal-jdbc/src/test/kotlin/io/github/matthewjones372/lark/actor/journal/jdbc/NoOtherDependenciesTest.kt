package io.github.matthewjones372.lark.actor.journal.jdbc

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-actor-journal-jdbc may put on a service's classpath: lark-actor's own, and nothing more. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
    )

    @Test
    fun `the main runtime classpath is lark-actor, and nothing else`() {
        val raw = System.getProperty("lark.actor.journal.jdbc.runtimeClasspath")
        withClue("the build must pass -Dlark.actor.journal.jdbc.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-actor-journal-jdbc must stay lark-actor and the JDK, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
