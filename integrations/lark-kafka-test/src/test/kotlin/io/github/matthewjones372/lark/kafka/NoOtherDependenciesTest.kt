package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeEmpty
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-kafka-test may put on a test classpath: lark-kafka, embedded-kafka, Jupiter's API, and what they bring. */
class NoOtherDependenciesTest {

    private fun jars(property: String): List<String> {
        val raw = System.getProperty(property).orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        withClue("the build must pass -D$property; see lark-kafka-test/build.gradle.kts") { raw.shouldNotBeEmpty() }
        return raw
    }

    @Test
    fun `the main runtime classpath is lark-kafka, embedded-kafka and Jupiter's API, and nothing beside them`() {
        val allowed = jars("lark.kafka.test.allowedClasspath").toSet()
        val unexpected = jars("lark.kafka.test.runtimeClasspath").filterNot { it in allowed }

        withClue("lark-kafka-test must stay lark-kafka, embedded-kafka and Jupiter's API, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
