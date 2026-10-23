package io.github.matthewjones372.lark.junit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath: lark, lark-assert, and the JUnit Platform's engine API. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        "junit-platform-engine",
        "junit-platform-commons",
        "opentest4j",
    )

    @Test
    fun `the main runtime classpath is lark, lark-assert and junit-platform-engine, and nothing else`() {
        val raw = System.getProperty("lark.junit.runtimeClasspath")
            ?: error("the build must pass -Dlark.junit.runtimeClasspath; see core/lark-junit/build.gradle.kts")

        raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } } shouldBe emptyList()
    }
}
