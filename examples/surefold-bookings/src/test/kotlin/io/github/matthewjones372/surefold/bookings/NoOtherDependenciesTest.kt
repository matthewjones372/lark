package io.github.matthewjones372.surefold.bookings

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBeEmpty
import java.io.File

/** What the example runs on: the data modules and what they bring, which is the AWS SDK for files and no more. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "surefold",
        // surefold-auth's Argon2id.
        "bcprov-jdk18on-",
        // surefold-files' S3 client, the core it shares with every client, and the JDK HTTP client it runs on.
        "s3-", "arns-", "auth-", "aws-core-", "aws-query-protocol-", "aws-xml-protocol-", "checksums-", "crt-core-",
        "endpoints-spi-", "eventstream-", "http-auth-", "http-client-spi-", "identity-spi-", "json-utils-",
        "metrics-spi-", "profiles-", "protocol-core-", "regions-", "retries-", "sdk-core-", "third-party-jackson-core-",
        "url-connection-client-", "utils-", "reactive-streams-", "slf4j-api-",
    )

    init {
        test("the main runtime classpath is the data modules and the S3 client, and nothing else") {
            val raw = System.getProperty("surefold.bookings.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.bookings.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
