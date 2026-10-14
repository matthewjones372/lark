package io.github.matthewjones372.lark.actor.remote.avro

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-actor-remote-avro may put on a service's classpath: lark-actor-remote's own, and Avro. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        // Avro, and the JSON, compression and logging libraries it declares.
        "avro-",
        "jackson-",
        "commons-codec-",
        "commons-io-",
        "commons-compress-",
        "commons-lang3-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is lark-actor-remote and Avro, and nothing else`() {
        val raw = System.getProperty("lark.actor.remote.avro.runtimeClasspath")
        withClue("the build must pass -Dlark.actor.remote.avro.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-actor-remote-avro must stay lark-actor-remote and Avro, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
