package io.github.matthewjones372.lark.app

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.typeOf

internal class Counter(val started: AtomicReference<String> = AtomicReference(""))

/** Declared as an object, which is what the checker finds and what a service writes. */
internal object Sampled : LarkApp<Counter>(typeOf<Counter>()) {

    override val module: Module = single<Counter> { Counter() }

    override fun AppScope.run(root: Counter) {
        root.started.set("ran")
    }
}

/** A graph short of a key, so the checker has something to fail on. */
internal object Faulty : LarkApp<Counter>(typeOf<Counter>()) {

    override val module: Module = single { _: File -> Counter() }

    override fun AppScope.run(root: Counter) = Unit
}

/** A graph that provides one key twice, which is a warning rather than a failure. */
internal object Warned : LarkApp<Counter>(typeOf<Counter>()) {

    override val module: Module = single<Counter> { Counter() } + single<Counter> { Counter() }

    override fun AppScope.run(root: Counter) = Unit
}

class LarkAppTest {

    @Test
    fun `an application carries the root it starts from`() {
        Sampled.root shouldBe typeOf<Counter>()
    }

    @Test
    fun `runApp starts the graph and hands the root to the declared block`() {
        val exit = runApp(Sampled)

        exit shouldBe ExitCode.Ok
    }

    @Test
    fun `a faulty graph leaves with a failure rather than running the block`() {
        runApp(Faulty) shouldBe ExitCode.Failed
    }

    @Test
    fun `an application is asked for its findings from the root it declares`() {
        val findings = Faulty.module.findings(Faulty.root)

        findings.map { it.severity } shouldContainExactly listOf(Severity.FAIL)
        findings.single().error.shouldBeMissingFile()
    }

    private fun WiringError.shouldBeMissingFile() {
        (this as WiringError.Missing).key shouldBe typeOf<File>()
        toString() shouldContain "File"
    }
}
