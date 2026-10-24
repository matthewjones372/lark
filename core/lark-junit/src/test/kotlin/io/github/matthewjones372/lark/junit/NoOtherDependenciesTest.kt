package io.github.matthewjones372.lark.junit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath: lark, lark-assert, lark-test and the JUnit engine API. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "junit-platform-engine",
        "junit-platform-commons",
        "opentest4j",
    )

    private val lark = Regex("""lark(-assert|-test)?(-\d.*)?\.jar""")

    @Test
    fun `the main runtime classpath is lark, lark-assert, lark-test and junit-platform-engine, and nothing else`() {
        val raw = System.getProperty("lark.junit.runtimeClasspath")
            ?: error("the build must pass -Dlark.junit.runtimeClasspath; see core/lark-junit/build.gradle.kts")

        raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> lark.matches(entry) || allowed.any { entry.startsWith(it) } } shouldBe emptyList()
    }
}
