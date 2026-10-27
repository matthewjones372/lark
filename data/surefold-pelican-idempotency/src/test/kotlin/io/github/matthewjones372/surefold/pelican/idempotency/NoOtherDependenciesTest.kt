package io.github.matthewjones372.surefold.pelican.idempotency

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-pelican-idempotency may put on a service's classpath: surefold-idempotency's and pelican-core's. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "pelican-core", "jackson-core")

    init {
        test("the main runtime classpath is surefold-idempotency and pelican-core, and nothing else") {
            val raw = System.getProperty("surefold.pelican.idempotency.runtimeClasspath")
            expect(raw != null) {
                "the build must pass -Dsurefold.pelican.idempotency.runtimeClasspath; see its build file"
            }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
