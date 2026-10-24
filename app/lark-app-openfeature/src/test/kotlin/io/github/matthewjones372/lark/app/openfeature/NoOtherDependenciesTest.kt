package io.github.matthewjones372.lark.app.openfeature

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: lark-app, lark-openfeature and the SDK. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "lark", "sdk-", "slf4j-api")

    @Test
    fun `the main runtime classpath is lark-app, lark-openfeature and the SDK, and nothing else`() {
        val raw = System.getProperty("lark.app.openfeature.runtimeClasspath")
        withClue("the build must pass -Dlark.app.openfeature.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-app-openfeature must stay lark-app plus lark-openfeature, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
