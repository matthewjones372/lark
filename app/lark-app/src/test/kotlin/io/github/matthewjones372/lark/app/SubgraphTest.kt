package io.github.matthewjones372.lark.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private class Prefs
private class Kept
private class Skipped
private class Ticker

class SubgraphTest {

    private fun graph(built: AtomicInteger) =
        single<Prefs> { Prefs() } +
            single { _: Prefs -> Kept() } +
            single<Ticker> { Ticker() } +
            single { _: Prefs -> built.incrementAndGet(); Skipped() }

    @Test
    fun `a subgraph builds only what its root needs`() {
        val skipped = AtomicInteger()

        testApp(graph(skipped).subgraph<Kept>()) { kept: Kept -> kept }

        withClue("Skipped and Ticker are not on the way to Kept") {
            skipped.get() shouldBe 0
        }
    }

    @Test
    fun `overriding replaces a key everything below already points at`() {
        val real = AtomicInteger()
        val module = (single<Prefs> { real.incrementAndGet(); Prefs() } + single { _: Prefs -> Kept() })
            .overriding(single<Prefs> { Prefs() })

        testApp(module) { _: Kept -> }

        withClue("the replaced recipe never runs, and Kept did not have to change") {
            real.get() shouldBe 0
        }
    }

    @Test
    fun `overriding a key nothing provides is refused`() {
        val failure = shouldThrow<IllegalArgumentException> {
            single<Prefs> { Prefs() }.overriding(single<Ticker> { Ticker() })
        }

        failure.message.orEmpty() shouldContain "Ticker"
    }

    @Test
    fun `testApp releases after the block fails`() {
        val released = AtomicBoolean()
        val module = single<Prefs> { install({ Prefs() }) { _, _ -> released.set(true) } }

        shouldThrow<AssertionError> {
            testApp(module) { _: Prefs -> throw AssertionError("the assertion the test made") }
        }

        released.get() shouldBe true
    }

    @Test
    fun `testApp fails with what the start could not do`() {
        val failure = shouldThrow<IllegalStateException> {
            testApp(single<Prefs> { Prefs() }) { _: Kept -> }
        }

        failure.message.orEmpty() shouldContain "Kept"
    }
}
