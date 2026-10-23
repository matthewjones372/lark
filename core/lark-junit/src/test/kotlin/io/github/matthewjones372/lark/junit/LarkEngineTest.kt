package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.samples.MixedSuite
import io.github.matthewjones372.lark.junit.samples.NotAnObject
import io.github.matthewjones372.lark.junit.samples.OrderedSuite
import io.github.matthewjones372.lark.junit.samples.UnacquirableSuite
import io.github.matthewjones372.lark.resource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import org.junit.platform.testkit.engine.Events

class LarkEngineTest {

    private fun run(selector: DiscoverySelector): EngineExecutionResults =
        EngineTestKit.engine("lark").selectors(selector).execute()

    private fun Events.names(): List<String> = map { it.testDescriptor.displayName }.toList()

    @Test
    fun `a pass, an assertion and a throw are reported under the tests' names`() {
        val tests = run(selectClass(MixedSuite::class.java)).testEvents()

        tests.succeeded().names() shouldBe listOf("passes")
        tests.failed().names() shouldBe listOf("fails an assertion", "throws something else")
        tests.failed().map { it.getRequiredPayload(TestExecutionResult::class.java).throwable.get() }.toList()
            .map { it::class } shouldBe listOf(AssertionError::class, IllegalStateException::class)
    }

    @Test
    fun `a test's unique ID is its suite's class and its name`() {
        val started = run(selectClass(MixedSuite::class.java)).testEvents().started().list().first()

        started.testDescriptor.uniqueId.toString() shouldBe
            "[engine:lark]/[suite:${MixedSuite::class.java.name}]/[test:passes]"
    }

    @Test
    fun `tests run one after another in the order they were registered, with the resource acquired once`() {
        OrderedSuite.seen.clear()

        run(selectClass(OrderedSuite::class.java)).testEvents().succeeded().count() shouldBe 3L

        OrderedSuite.seen shouldBe listOf("first db", "second db", "third db", "released")
    }

    @Test
    fun `a suite whose resource cannot be acquired fails, and none of its tests start`() {
        val events = run(selectClass(UnacquirableSuite::class.java))

        events.testEvents().started().count() shouldBe 0L
        events.containerEvents().failed().names() shouldBe listOf(UnacquirableSuite::class.java.simpleName)
    }

    @Test
    fun `a package selector finds the suite objects in it and passes over a suite class`() {
        val suites = run(selectPackage(MixedSuite::class.java.packageName)).containerEvents().started().names()

        suites.sorted() shouldBe listOf("Lark", "MixedSuite", "OrderedSuite", "UnacquirableSuite")
        run(selectClass(NotAnObject::class.java)).testEvents().started().count() shouldBe 0L
    }

    @Test
    fun `two tests with one name are refused, because the name is the test's ID`() {
        val refused = shouldThrow<IllegalArgumentException> {
            object : Suite<Unit>() {
                override val shared: Resource<Unit> = resource { }

                init {
                    test("twice") { }
                    test("twice") { }
                }
            }
        }

        refused.message.orEmpty() shouldContain "\"twice\""
    }
}
