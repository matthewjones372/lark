package io.github.matthewjones372.lark.cluster

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "arrow-", "lark")

    @Test
    fun `the main runtime classpath is lark-actor-remote and what it brings, and nothing else`() {
        val raw = System.getProperty("lark.cluster.runtimeClasspath")
        withClue("the build must pass -Dlark.cluster.runtimeClasspath; see lark-cluster/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-cluster must stay lark-actor-remote and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
