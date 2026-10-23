package io.github.matthewjones372.lark.arrow

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: lark, and Arrow's core. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "arrow-", "lark")

    @Test
    fun `the main runtime classpath is lark and arrow-core, and nothing else`() {
        val raw = System.getProperty("lark.arrow.runtimeClasspath")
        withClue("the build must pass -Dlark.arrow.runtimeClasspath; see core/lark-arrow/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-arrow must stay lark plus arrow-core, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
