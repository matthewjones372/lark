package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.resource

/** Run by this module's own `test` task, so its tests appear in Gradle's JUnit XML beside Jupiter's. */
object PassingSuite : SharedSuite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    init {
        test("the shared value reaches the test") { two -> expect(two == 2) }
        test("a test whose expectation holds passes") { expect(true) }
    }
}
