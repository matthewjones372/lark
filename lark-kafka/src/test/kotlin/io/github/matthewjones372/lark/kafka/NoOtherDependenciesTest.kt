package io.github.matthewjones372.lark.kafka

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-kafka may put on a consumer's classpath: lark-stream's own and the Kafka client. No backend. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        // lark-stream and what it is allowed: lark, the Kotlin standard library and Arrow.
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        // The Kafka client, and the compression codecs and logging facade it declares.
        "kafka-clients-",
        "zstd-jni-",
        "lz4-java-",
        "snappy-java-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is lark-stream and the Kafka client, and no backend`() {
        val raw = System.getProperty("lark.kafka.runtimeClasspath")
        withClue("the build must pass -Dlark.kafka.runtimeClasspath; see lark-kafka/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-kafka must stay lark-stream and the Kafka client, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
