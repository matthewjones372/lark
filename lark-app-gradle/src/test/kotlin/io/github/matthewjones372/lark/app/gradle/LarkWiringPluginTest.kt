package io.github.matthewjones372.lark.app.gradle

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
        val checker = System.getProperty("lark.checker.repo")
        val version = System.getProperty("lark.checker.version")
        withClue("the build must pass -Dlark.app.underTest; see lark-app-gradle/build.gradle.kts") {
            underTest.shouldNotBeNull()
        }
        withClue("the build must pass -Dlark.checker.repo; see lark-app-gradle/build.gradle.kts") {
            checker.shouldNotBeNull()
        }
        val jars = underTest!!.split(File.pathSeparator).joinToString(", ") { "\"$it\"" }

        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$checker") }
                    gradlePluginPortal()
                }
            }

            rootProject.name = "under-test"
            """.trimIndent(),
        )
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("io.github.matthewjones372.lark.wiring") version "$version"
            }

            // Where the compiler plugin comes from; the Kotlin plugin resolves it by coordinates.
            repositories {
                maven { url = uri("$checker") }
                mavenCentral()
            }

            dependencies { implementation(files($jars)) }

            $wiring
            """.trimIndent(),
        )
        val source = File(dir, "src/main/kotlin/Under.kt")
        source.parentFile.mkdirs()
        source.writeText(graph)
    }

    private fun run(dir: File, vararg args: String) = runner(dir, *args).buildAndFail()

    private fun runner(dir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*args, "--stacktrace")

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

        val result = runner(dir, "check").build()

        result.task(":larkWiring")?.outcome shouldBe TaskOutcome.SUCCESS
        File(dir, "build/reports/lark/Under.mmd").readText() shouldContain "graph TD"
    }

    @Test
    fun `the checker runs inside the compiler, which is what puts a fault in the editor`(@TempDir dir: File) {
        project(dir, "larkWiring { verbose = true }", SOUND)

        val result = runner(dir, "compileKotlin").build()

        withClue("the same checkers the IDE runs in K2 mode, so this is the editor's answer too") {
            result.output shouldContain "lark-app: Under provides [Pump]"
        }
    }

    @Test
    fun `the reader agrees with the graph the task builds by running it`(@TempDir dir: File) {
        project(dir, "larkWiring { verbose = true }", FAULTY)

        val result = runner(dir, "compileKotlin").build()

        withClue("the whole claim is that this answers what larkWiring answers, only sooner") {
            result.output shouldContain "provides [Pump], and is short of [DataSource]"
        }
    }

    @Test
    fun `a module written as a chain, a name and a choice is read through all three`(@TempDir dir: File) {
        project(dir, "larkWiring { verbose = true }", COMPOSED)

        runner(dir, "compileKotlin").build().output shouldContain
            "provides [DataSource, Pump, Valve], and is short of []"
    }

    @Test
    fun `a shape the reader does not know abandons the application rather than guessing`(@TempDir dir: File) {
        project(dir, "larkWiring { verbose = true }", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("a red line under working code costs more than saying nothing") {
            result.output shouldContain "was not read: gave up at"
            result.output shouldNotContain "is short of"
        }
    }

    @Test
    fun `the compiler says nothing about a graph unless it is asked to`(@TempDir dir: File) {
        project(dir, "", SOUND)

        runner(dir, "compileKotlin").build().output shouldNotContain "lark-app: checking"
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

/** A chain, a name it is reached through, and a choice whose branches are unioned. */
private const val COMPOSED = """$IMPORTS
class Valve

private val chosen: Module =
    if (System.getenv("X") == null) single<DataSource> { DataSource() } else single<DataSource> { DataSource() }

private val named: Module = single { _: DataSource -> Pump() } + single { _: DataSource -> Valve() }

object Under : LarkApp<Pump>() {
    override val module: Module = named + chosen
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** A module out of a collection: legal, sound, and nothing a reader can follow. */
private const val UNREADABLE = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module =
        listOf(single<DataSource> { DataSource() }, single { _: DataSource -> Pump() }).reduce { a, b -> a + b }
    override fun AppScope.run(root: Pump) = Unit
}
"""

private const val WARNED = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module = single<Pump> { Pump() } + single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""
