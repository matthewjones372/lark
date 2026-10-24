package io.github.matthewjones372.lark.openfeature

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: lark, and the SDK alone. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "lark",
        "sdk-",
        // The SDK logs through it and fails without it (spec 0180, open question 1).
        "slf4j-api",
    )

    @Test
    fun `the main runtime classpath is lark and the OpenFeature SDK, and nothing else`() {
        val raw = System.getProperty("lark.openfeature.runtimeClasspath")
        withClue("the build must pass -Dlark.openfeature.runtimeClasspath; see lark-openfeature/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-openfeature must stay lark plus the OpenFeature SDK, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
