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
    fun `a graph the compiler could not judge is still caught by the task that runs it`(@TempDir dir: File) {
        // Unreadable on purpose: the compiler gives up on it, so this is the task's catch alone,
        // which is the division of work the whole design rests on.
        project(dir, "", UNREADABLE_AND_FAULTY)

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
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
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
    fun `a missing key is a compiler error on the recipe that asked`(@TempDir dir: File) {
        project(dir, "", FAULTY)

        val result = run(dir, "compileKotlin")

        withClue("the whole ask was an error while you type, and this is the compiler's own") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        }
        withClue("the sentence larkWiring prints for the same fault, so a reader meets one wording") {
            result.output shouldContain "Pump needs DataSource, and nothing builds it"
        }
    }

    @Test
    fun `the reader's copy of what lark's own factories need has not gone stale`(@TempDir dir: File) {
        project(dir, "larkWiring { verbose = true }", LIBRARY)

        val result = runner(dir, "compileKotlin").build()

        withClue("actor takes an ActorSystem and config takes a Config; the reader says so from a list") {
            result.output shouldContain "provides [ActorRef<Ping>, ActorSystem, Config, Settings], and is short of []"
        }
    }

    @Test
    fun `a graph the reader could not follow says so, whether or not it was asked`(@TempDir dir: File) {
        project(dir, "", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("otherwise silence means both a sound graph and a graph nobody looked at") {
            result.output shouldContain "this graph was not read here, and is checked by larkWiring alone"
        }
    }

    @Test
    fun `a sound graph compiles without a word`(@TempDir dir: File) {
        project(dir, "", SOUND)

        runner(dir, "compileKotlin").build().output shouldNotContain "lark-app"
    }

    @Test
    fun `a graph the reader gave up on compiles, and is left to the task`(@TempDir dir: File) {
        project(dir, "", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("a red line under working code costs more than a fault found a moment later") {
            result.output shouldNotContain "nothing builds it"
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
    fun `a node the root does not reach is named at the top of what it took with it`(@TempDir dir: File) {
        project(dir, "larkWiring { verbose = true }", FORGOTTEN)

        val result = runner(dir, "compileKotlin").build()

        withClue("one module left out is one line to change, not one line per node under it") {
            result.output shouldContain "and nothing reaches [Audit]"
        }
    }

    @Test
    fun `a root the graph does not build says nothing about what reaches it`(@TempDir dir: File) {
        project(dir, "larkWiring { verbose = true }", ROOTLESS)

        val result = runner(dir, "compileKotlin").build()

        withClue("an unmatched root unreaches every node at once, which is the one wrong answer") {
            result.output shouldNotContain "nothing reaches"
        }
    }

    @Test
    fun `a node nothing reaches is a warning on the recipe that built it`(@TempDir dir: File) {
        project(dir, "", FORGOTTEN)

        val result = runner(dir, "compileKotlin").build()

        withClue("the sentence larkWiring prints for the same fault, so a reader meets one wording") {
            result.output shouldContain "lark-app: nothing reaches Audit, and it is built on every start"
        }
        withClue("a node built for nothing is waste, not a broken graph") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
        }
        withClue("one edit put Ledger out of reach too; naming it is that edit reported twice") {
            result.output shouldNotContain "nothing reaches Ledger"
        }
    }

    @Test
    fun `a graph the reader gave up on says nothing about what reaches what`(@TempDir dir: File) {
        project(dir, "", UNREADABLE)

        withClue("a warning under correct code costs more than one larkWiring prints a moment later") {
            runner(dir, "compileKotlin").build().output shouldNotContain "nothing reaches"
        }
    }

    @Test
    fun `a key two recipes provide is a warning on the one that wins`(@TempDir dir: File) {
        project(dir, "", TWICE)

        val result = runner(dir, "compileKotlin").build()

        val shadowed = TWICE.lines().indexOfFirst { it.contains("single<Pump>") } + 1

        withClue("the sentence larkWiring prints for the same fault, and the line it names") {
            result.output shouldContain "lark-app: Pump is provided twice; this one wins over Under.kt:$shadowed"
        }
        withClue("a shadowed recipe is a warning; only the task decides whether one stops a build") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
        }
    }

    @Test
    fun `a replacement that says it is one is not an accident to report`(@TempDir dir: File) {
        project(dir, "", OVERRIDDEN)

        withClue("overriding forgives the collisions its own merge introduces, and so does this") {
            runner(dir, "compileKotlin").build().output shouldNotContain "provided twice"
        }
    }

    @Test
    fun `the branches of a choice are alternatives, not two recipes for one key`(@TempDir dir: File) {
        project(dir, "", COMPOSED)

        withClue("every branch provides DataSource and every assembly gets one of them") {
            runner(dir, "compileKotlin").build().output shouldNotContain "provided twice"
        }
    }

    @Test
    fun `two recipes that need each other are an error naming the ring`(@TempDir dir: File) {
        project(dir, "", RING)

        val result = run(dir, "compileKotlin")

        withClue("a cycle is a FAIL at runtime, so it is an error here") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        }
        withClue("the path larkWiring prints for the same ring, in the same order") {
            result.output shouldContain "lark-app: cycle DataSource -> Pump -> DataSource"
        }
    }

    @Test
    fun `a graph the reader gave up on says nothing about a ring either`(@TempDir dir: File) {
        project(dir, "", UNREADABLE)

        runner(dir, "compileKotlin").build().output shouldNotContain "cycle"
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

/**
 * Every lark factory the reader knows by name, compiled against the real modules.
 *
 * The reader holds its own copy of what each of these takes — `actor` an `ActorSystem`, `config` a
 * `Config`, `loadedConfig` nothing — and this is the only build that would notice that copy going
 * stale. Change one of those in the library and this test says so.
 */
private const val LIBRARY = """
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.pekko.actor
import io.github.matthewjones372.lark.app.typesafe.config
import io.github.matthewjones372.lark.app.typesafe.loadedConfig
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors

class Ping
class Settings(val port: Int)

object Under : LarkApp<Settings>() {
    override val module: Module =
        single<ActorSystem> { ActorSystem.create("under") } +
            loadedConfig() +
            config<Settings>("under") { Settings(int("port")) } +
            actor<Ping>("ping") { Behaviors.empty<Ping>() }
    override fun AppScope.run(root: Settings) = Unit
}
"""

/** Unreadable and short of a key: what only a task that runs the graph can catch. */
private const val UNREADABLE_AND_FAULTY = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module = listOf(single { _: DataSource -> Pump() }).reduce { a, b -> a + b }
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

/** A node nothing takes, and a node only that one takes: one edit, and so one thing to say. */
private const val FORGOTTEN = """$IMPORTS
class Ledger
class Audit(val ledger: Ledger)

object Under : LarkApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() } + single<Ledger> { Ledger() } + single { l: Ledger -> Audit(l) }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** A graph that builds no root: sound to read, and unanswerable about what reaches what. */
private const val ROOTLESS = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module = single<DataSource> { DataSource() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Two recipes for one key, on two lines, so the report has a line to name that is not its own. */
private const val TWICE = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() } +
            single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** The same collision, said out loud. */
private const val OVERRIDDEN = """
import io.github.matthewjones372.lark.app.AppScope
import io.github.matthewjones372.lark.app.LarkApp
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single

class Pump

object Under : LarkApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() }.overriding(single<Pump> { Pump() })
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Each of two recipes needing the other: legal to write, impossible to assemble. */
private const val RING = """$IMPORTS
object Under : LarkApp<Pump>() {
    override val module: Module =
        single { _: DataSource -> Pump() } + single { _: Pump -> DataSource() }
    override fun AppScope.run(root: Pump) = Unit
}
"""
