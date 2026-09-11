package io.github.matthewjones372.lark.app.gradle

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A build that runs, because what the plugin does is register a task and fail a build. The lark-app
 * under test is this repository's own, handed over as a file dependency.
 *
 * The generated project declares no toolchain: it has no resolver to provision one with, and the
 * matrix runner holds only the JDK it was set up with.
 */
class LarkWiringPluginTest {

    private fun project(dir: File, wiring: String, graph: String) {
        val underTest = System.getProperty("lark.app.underTest")
        withClue("the build must pass -Dlark.app.underTest; see lark-app-gradle/build.gradle.kts") {
            underTest.shouldNotBeNull()
        }
        val jars = underTest!!.split(File.pathSeparator).joinToString(", ") { "\"$it\"" }

        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "under-test"""")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("io.github.matthewjones372.lark.wiring")
            }

            repositories { mavenCentral() }

            dependencies { implementation(files($jars)) }

            $wiring
            """.trimIndent(),
        )
        val source = File(dir, "src/main/kotlin/Under.kt")
        source.parentFile.mkdirs()
        source.writeText(graph)
    }

    private fun run(dir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")
            .buildAndFail()

    @Test
    fun `a missing key fails the build and names the recipe that asked`(@TempDir dir: File) {
        project(dir, "", FAULTY)

        val result = run(dir, "larkWiring")

        result.task(":larkWiring")?.outcome shouldBe TaskOutcome.FAILED
        // Also the only place the no-reflect label is exercised: this generated project has
        // kotlin-reflect nowhere, and without stripping its notice the sentence reads
        // "Pump (Kotlin reflection is not available) needs DataSource (Kotlin reflection ...)".
        result.output shouldContain "Pump needs DataSource, and nothing builds it"
        withClue("the IDE makes an entry in the Build window out of the compiler's own shape") {
            // Canonical, because a temporary directory on this platform is reached through a symlink
            // and the checker reports the path it actually opened.
            val source = File(dir, "src/main/kotlin/Under.kt").canonicalPath
            result.output shouldContain "e: file://$source:"
        }
    }

    @Test
    fun `compiling is enough to fail, without waiting for check or for a run`(@TempDir dir: File) {
        project(dir, "", FAULTY)

        val result = run(dir, "classes")

        withClue("the whole ask is that the fault arrives where a compile error would") {
            result.task(":larkWiring")?.outcome shouldBe TaskOutcome.FAILED
        }
        result.output shouldContain "Pump needs DataSource"
    }

    @Test
    fun `a sound graph passes check and leaves a diagram behind`(@TempDir dir: File) {
        project(dir, "", SOUND)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments("check", "--stacktrace")
            .build()

        result.task(":larkWiring")?.outcome shouldBe TaskOutcome.SUCCESS
        File(dir, "build/reports/lark/Under.mmd").readText() shouldContain "graph TD"
    }

    @Test
    fun `a warning stops the build where the extension asks it to`(@TempDir dir: File) {
        project(dir, "larkWiring { failOn = \"WARN\" }", WARNED)

        run(dir, "larkWiring").output shouldContain "provided twice"
    }
}

private const val IMPORTS = """
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import kotlin.reflect.typeOf

class Pump
class DataSource
"""

private const val FAULTY = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module = single { _: DataSource -> Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

private const val SOUND = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module = single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

private const val WARNED = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module = single<Pump> { Pump() } + single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""
