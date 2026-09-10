package io.github.matthewjones372.lark.app

import arrow.core.Either
import arrow.core.nonEmptyListOf
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.typeOf

private class Held
private class Absent

class RunTest {

    @Test
    fun `awaitShutdown returns when the process is asked to stop`() {
        val shutdown = Shutdown()
        val running = CountDownLatch(1)
        val outcome = AtomicReference<Either<StartupError, Unit>>()

        val thread = Thread {
            outcome.set(
                (single<Held> { Held() }).application(shutdown) { _: Held ->
                    running.countDown()
                    awaitShutdown()
                },
            )
        }
        thread.start()
        running.await()
        shutdown.request()
        thread.join(5_000)

        outcome.get().shouldNotBeNull().getOrNull().shouldNotBeNull()
    }

    @Test
    fun `the graph is released after the block returns`() {
        val released = CountDownLatch(1)
        val module = single<Held> { install({ Held() }) { _, _ -> released.countDown() } }

        module.application(Shutdown()) { _: Held -> }.getOrNull().shouldNotBeNull()

        released.count shouldBe 0L
    }

    @Test
    fun `a graph that cannot start never runs the block`() {
        val ran = AtomicReference(false)

        val error = (single<Held> { Held() }).application(Shutdown()) { _: Absent -> ran.set(true) }
            .leftOrNull().shouldNotBeNull()

        ran.get() shouldBe false
        error shouldBe StartupError.NoSuchNode(typeOf<Absent>())
    }

    @Test
    fun `a refusal describes itself by node and reason`() {
        val described = StartupError.Refused(typeOf<Held>(), "the port is already taken").describe()

        described shouldContain "Held"
        described shouldContain "the port is already taken"
    }

    @Test
    fun `a faulty graph describes itself with the wiring report`() {
        val described = StartupError
            .Unwireable(nonEmptyListOf(WiringError.Missing(typeOf<Held>(), typeOf<Absent>())))
            .describe()

        described shouldContain "❯ missing Held"
        described shouldContain "❯     for Absent"
    }

    @Test
    fun `an exit code is Ok only when the application left cleanly`() {
        Either.Right(Unit).exitCode() shouldBe ExitCode.Ok
        Either.Left(StartupError.NoSuchNode(typeOf<Absent>())).exitCode() shouldBe ExitCode.Failed
    }

    @Test
    fun `runApp answers rather than ending the process, so a test can read what it decided`() {
        val started = AtomicReference(false)

        val exit = runApp(single<Held> { Held() }) { _: Held -> started.set(true) }

        started.get() shouldBe true
        exit shouldBe ExitCode.Ok
    }

    @Test
    fun `a graph that cannot start answers Failed`() {
        runApp(single<Held> { Held() }) { _: Absent -> } shouldBe ExitCode.Failed
    }
}
