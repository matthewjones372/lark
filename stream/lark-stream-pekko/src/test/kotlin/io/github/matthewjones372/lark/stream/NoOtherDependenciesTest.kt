package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What lark-stream-pekko is allowed to put on a consumer's classpath, stated
 * as a test: lark-stream and everything it brings, lark-pekko, Pekko Streams
 * with the Scala runtime and the libraries Pekko itself needs. No HTTP
 * library, no JSON library, no coroutines, no second functional stack.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        // The description this module runs, the lark it is written in, and the
        // lark-pekko whose `await` it waits through.
        "lark",
        "lark-stream",
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
    fun `the main runtime classpath is lark-stream, lark-pekko and pekko-stream, and nothing else`() {
        val raw = System.getProperty("lark.stream.runtimeClasspath")
        withClue("the build must pass -Dlark.stream.runtimeClasspath; see lark-stream-pekko/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-stream-pekko must stay lark-stream, pekko-stream and lark-pekko, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
