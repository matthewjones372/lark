package io.github.matthewjones372.lark.micrometer

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: lark, and the registry. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        // The registry interface, and what micrometer-core itself brings.
        "micrometer-core",
        "micrometer-commons",
        "micrometer-observation",
        "HdrHistogram",
        "LatencyUtils",
    )

    @Test
    fun `the main runtime classpath is lark and Micrometer, and nothing else`() {
        val raw = System.getProperty("lark.micrometer.runtimeClasspath")
        withClue("the build must pass -Dlark.micrometer.runtimeClasspath; see lark-micrometer/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-micrometer must stay lark plus Micrometer, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
