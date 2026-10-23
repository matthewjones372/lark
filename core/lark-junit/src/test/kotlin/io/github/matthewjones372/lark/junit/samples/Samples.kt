package io.github.matthewjones372.lark.junit.samples

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.resource

// Suites that fail on purpose. This module's own `test` task excludes this package, so only the
// tests that select them through EngineTestKit or a Launcher run them.

object MixedSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("passes") { }
        test("fails an assertion") { throw AssertionError("expected 1 but was 2") }
        test("throws something else") { error("the database was not there") }
    }
}

/** Records what each test was handed and when the resource was released, for the engine test to read. */
object OrderedSuite : Suite<String>() {
    val seen: MutableList<String> = mutableListOf()

    override val shared: Resource<String> = resource {
        install({ "db" }) { _, _ -> seen += "released" }
    }

    init {
        test("first") { db -> seen += "first $db" }
        test("second") { db -> seen += "second $db" }
        test("third") { db -> seen += "third $db" }
    }
}

object UnacquirableSuite : Suite<String>() {
    override val shared: Resource<String> = resource { error("no database") }

    init {
        test("never runs") { }
    }
}

/** Has a suite's shape but is a class, so there is no one instance to run. */
class NotAnObject : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("never found") { }
    }
}
