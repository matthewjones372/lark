package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this library is allowed to put on a consumer's classpath, stated as a
 * test: the Kotlin standard library, Pekko Streams with the Scala runtime and
 * the two libraries Pekko itself needs, and Arrow. No HTTP library, no JSON
 * library, no coroutines, no second functional stack.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "pekko-",
        // What pekko-stream brings with it: the Scala runtime it is written
        // in, Typesafe Config, the Reactive Streams interfaces its operators
        // implement, and the TLS settings parser pekko-stream's TLS stage
        // reads. Every one of these arrives through pekko-stream and none is
        // declared here.
        "scala-library",
        "config-",
        "reactive-streams-",
        "ssl-config-core_",
    )

    @Test
    fun `the main runtime classpath is pekko-stream and arrow, and nothing else`() {
        val raw = System.getProperty("dipper.core.runtimeClasspath")
        withClue("the build must pass -Ddipper.core.runtimeClasspath; see dipper-core/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("dipper-core must stay pekko-stream plus arrow, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
