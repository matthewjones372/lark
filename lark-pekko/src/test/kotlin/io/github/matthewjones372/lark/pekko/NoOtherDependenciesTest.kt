package io.github.matthewjones372.lark.pekko

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test: lark and Arrow, and pekko-actor with what it brings. No HTTP library,
 * no JSON library, no coroutines.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        "pekko-actor",
        // What pekko-actor brings with it: the Scala runtime it is written in
        // and Typesafe Config, which is what a dispatcher is configured in.
        // Both arrive through pekko-actor and neither is declared here.
        "scala-library",
        "config-",
    )

    @Test
    fun `the main runtime classpath is lark and pekko-actor, and nothing else`() {
        val raw = System.getProperty("lark.pekko.runtimeClasspath")
        withClue("the build must pass -Dlark.pekko.runtimeClasspath; see lark-pekko/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-pekko must stay lark plus pekko-actor, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
