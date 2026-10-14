package io.github.matthewjones372.lark.app.cluster

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "arrow-", "lark", "config-")

    @Test
    fun `the main runtime classpath is lark-app-actor, lark-app-typesafe and lark-cluster, and no backend`() {
        val raw = System.getProperty("lark.app.cluster.runtimeClasspath")
        withClue("the build must pass -Dlark.app.cluster.runtimeClasspath; see lark-app-cluster/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-app-cluster must bring no discovery backend of its own, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
