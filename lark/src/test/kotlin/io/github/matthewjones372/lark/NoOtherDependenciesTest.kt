package io.github.matthewjones372.lark

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test — the claim `AGENTS.md` makes for it: Arrow and the JDK, and nothing
 * else. No JSON library, no HTTP library, no coroutines.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "arrow-")

    @Test
    fun `the main runtime classpath is arrow, and nothing else`() {
        val raw = System.getProperty("lark.runtimeClasspath")
        withClue("the build must pass -Dlark.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark must stay Arrow and the JDK, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
