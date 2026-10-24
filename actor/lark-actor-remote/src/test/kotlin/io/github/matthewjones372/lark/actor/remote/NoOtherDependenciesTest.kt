package io.github.matthewjones372.lark.actor.remote

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "lark")

    @Test
    fun `the main runtime classpath is lark-actor and what it brings, and nothing else`() {
        val raw = System.getProperty("lark.actor.remote.runtimeClasspath")
        withClue("the build must pass -Dlark.actor.remote.runtimeClasspath; see lark-actor-remote/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-actor-remote must stay lark-actor and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
