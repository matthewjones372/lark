package io.github.matthewjones372.lark.cluster.aws

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-cluster-aws may put on a service's classpath: lark-cluster's own, and three clients of the AWS SDK. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "arrow-",
        "lark",
        // The AWS SDK's three clients, the core they share, and the JDK HTTP client they run on.
        "servicediscovery-",
        "ecs-",
        "dynamodb-",
        "url-connection-client-",
        "auth-",
        "aws-core-",
        "aws-json-protocol-",
        "checksums-",
        "endpoints-spi-",
        "http-auth-",
        "http-client-spi-",
        "identity-spi-",
        "json-utils-",
        "metrics-spi-",
        "profiles-",
        "protocol-core-",
        "regions-",
        "retries-",
        "sdk-core-",
        "third-party-jackson-core-",
        "utils-",
        "eventstream-",
        "reactive-streams-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is lark-cluster and the AWS SDK's clients, and nothing else`() {
        val raw = System.getProperty("lark.cluster.aws.runtimeClasspath")
        withClue("the build must pass -Dlark.cluster.aws.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-cluster-aws must stay lark-cluster and the AWS SDK's clients, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
