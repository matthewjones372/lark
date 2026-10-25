package io.github.matthewjones372.surefold.junit.samples

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.holds
import org.opentest4j.AssertionFailedError

/** Checks made by throwing, as Playwright's and pelican's are. */
object HoldsSuite : Suite() {
    init {
        test("holds when the block returns") { holds { } }
        test("fails once for each block that throws an assertion") {
            holds { throw AssertionError("the golden file differs") } and
                holds { } and
                holds { throw AssertionError("the stub file differs") }
        }
        test("keeps what an assertion expected and found") {
            holds { throw AssertionFailedError("the tile's text", "Start load", "Stop load") }
        }
        test("errors on anything else thrown") { holds { error("the page never loaded") } }
    }
}
