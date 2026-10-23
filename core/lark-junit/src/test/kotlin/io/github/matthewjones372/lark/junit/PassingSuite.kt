package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.resource

/** Run by this module's own `test` task, so its tests appear in Gradle's JUnit XML beside Jupiter's. */
object PassingSuite : Suite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    init {
        test("the shared value reaches the test") { two -> check(two == 2) }
        test("a test that returns passes") { }
    }
}
