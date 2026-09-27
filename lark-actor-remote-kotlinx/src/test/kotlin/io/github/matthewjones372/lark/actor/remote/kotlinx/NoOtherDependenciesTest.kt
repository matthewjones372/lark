package io.github.matthewjones372.lark.actor.remote.kotlinx

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-actor-remote-kotlinx may put on a service's classpath: lark-actor-remote's own, and kotlinx's ProtoBuf. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "arrow-", "lark", "kotlinx-serialization-")

    @Test
    fun `the main runtime classpath is lark-actor-remote and kotlinx serialization, and nothing else`() {
        val raw = System.getProperty("lark.actor.remote.kotlinx.runtimeClasspath")
        withClue("the build must pass -Dlark.actor.remote.kotlinx.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-actor-remote-kotlinx must stay lark-actor-remote and kotlinx, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
