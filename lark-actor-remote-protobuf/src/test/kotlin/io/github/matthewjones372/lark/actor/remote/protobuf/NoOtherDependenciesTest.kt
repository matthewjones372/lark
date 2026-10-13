package io.github.matthewjones372.lark.actor.remote.protobuf

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-actor-remote-protobuf may put on a service's classpath: lark-actor-remote's own, and Protobuf. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "arrow-", "lark", "protobuf-java-")

    @Test
    fun `the main runtime classpath is lark-actor-remote and Protobuf, and nothing else`() {
        val raw = System.getProperty("lark.actor.remote.protobuf.runtimeClasspath")
        withClue("the build must pass -Dlark.actor.remote.protobuf.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-actor-remote-protobuf must stay lark-actor-remote and Protobuf, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
