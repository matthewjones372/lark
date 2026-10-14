package io.github.matthewjones372.lark.otel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: lark, and the API alone. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        // The API and the context it defines. No SDK: a service brings its own,
        // with the exporter and the agent it has chosen.
        "opentelemetry-api",
        "opentelemetry-context",
    )

    @Test
    fun `the main runtime classpath is lark and the OpenTelemetry API, and nothing else`() {
        val raw = System.getProperty("lark.otel.runtimeClasspath")
        withClue("the build must pass -Dlark.otel.runtimeClasspath; see lark-otel/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-otel must stay lark plus the OpenTelemetry API, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
