package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeLessThanOrEqual
import io.github.matthewjones372.surefold.assert.shouldContain
import io.github.matthewjones372.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.junit.samples.BrokenLeaseSuite
import io.github.matthewjones372.surefold.junit.samples.CountedGuard
import io.github.matthewjones372.surefold.junit.samples.FailingLeaseSuite
import io.github.matthewjones372.surefold.junit.samples.LeasingSuite
import io.github.matthewjones372.surefold.junit.samples.UnusedGuard
import io.github.matthewjones372.surefold.junit.samples.UsingSuite
import io.github.matthewjones372.surefold.junit.testkit.failureOf
import io.github.matthewjones372.surefold.junit.testkit.failures
import io.github.matthewjones372.surefold.resource
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.junit.platform.testkit.engine.Events

/** A guarded resource is acquired once per run, on first use, and lent to at most its permits' worth of tests. */
class GuardedTest {

    private fun run(vararg suites: Class<*>): Events =
        EngineTestKit.engine("surefold").selectors(*suites.map { selectClass(it) }.toTypedArray()).execute()
            .testEvents()

    @Test
    fun `fifty tests that lease or use a guard of three permits never see more than three holders`() {
        val acquired = CountedGuard.acquired.get()
        val released = CountedGuard.released.get()
        val unused = UnusedGuard.acquired.get()

        val events = run(LeasingSuite::class.java, UsingSuite::class.java)

        events.succeeded().count() shouldBe 100L
        CountedGuard.last!!.most.get() shouldBeLessThanOrEqual 3
        CountedGuard.acquired.get() - acquired shouldBe 1
        CountedGuard.released.get() - released shouldBe 1
        UnusedGuard.acquired.get() - unused shouldBe 0
    }

    @Test
    fun `a test that fails gives its permit back`() {
        run(FailingLeaseSuite::class.java).failures().values.map { it.message }.sortedBy { it } shouldBe
            listOf("failure 1", "failure 2", "failure 3")
    }

    @Test
    fun `a guard that cannot be acquired fails the tests that lease it`() {
        run(BrokenLeaseSuite::class.java).failureOf("never gets the database").message shouldBe
            "the database did not start"
    }

    @Test
    fun `a guard used outside a run of the engine says so`() {
        shouldThrow<IllegalStateException> { CountedGuard.use { } }.message.orEmpty() shouldContain
            "CountedGuard was used outside a run of the surefold engine"
    }

    @Test
    fun `a guard needs at least one permit`() {
        shouldThrow<IllegalArgumentException> {
            object : Guarded<Unit>(permits = 0) {
                override val resource = resource { }
            }
        }.message.orEmpty() shouldContain "has 0 permits; a guard needs at least one"
    }
}
