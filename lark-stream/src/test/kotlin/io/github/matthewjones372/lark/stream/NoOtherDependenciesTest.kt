package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What lark-stream is allowed to put on a consumer's classpath, stated as a
 * test: the Kotlin standard library, lark and lark-pekko, Pekko Streams with
 * the Scala runtime and the libraries Pekko itself needs, and Arrow. No HTTP
 * library, no JSON library, no coroutines, no second functional stack.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        // The module this one is a stream over, and the one whose `await` it
        // waits through; Arrow arrives through the first of them.
        "lark",
        "lark-pekko",
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
    fun `the main runtime classpath is lark, lark-pekko, pekko-stream and arrow, and nothing else`() {
        val raw = System.getProperty("lark.stream.runtimeClasspath")
        withClue("the build must pass -Dlark.stream.runtimeClasspath; see lark-stream/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-stream must stay pekko-stream, lark, lark-pekko and arrow, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
