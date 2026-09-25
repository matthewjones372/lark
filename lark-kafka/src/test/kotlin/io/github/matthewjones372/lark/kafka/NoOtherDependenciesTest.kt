package io.github.matthewjones372.lark.kafka

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What lark-kafka may put on a consumer's classpath: lark-stream's own, and Pekko's Kafka connector
 * with the client it brings.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        // lark-stream and everything it is allowed.
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        "pekko-",
        "scala-library",
        "config-",
        "reactive-streams-",
        "ssl-config-core_",
        // The connector's client, and the compression codecs and logging facade the client declares.
        "kafka-clients-",
        "zstd-jni-",
        "lz4-java-",
        "snappy-java-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is lark-stream and the Kafka connector, and nothing else`() {
        val raw = System.getProperty("lark.kafka.runtimeClasspath")
        withClue("the build must pass -Dlark.kafka.runtimeClasspath; see lark-kafka/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-kafka must stay lark-stream and the Kafka connector, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
