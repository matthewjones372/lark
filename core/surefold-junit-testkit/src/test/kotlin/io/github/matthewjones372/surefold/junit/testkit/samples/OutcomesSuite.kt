package io.github.matthewjones372.surefold.junit.testkit.samples

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.ignore
import io.github.matthewjones372.surefold.junit.shouldBe

/** A test that ends each way, for the matchers to read. */
object OutcomesSuite : Suite() {
    init {
        test("passes") { 2 shouldBe 2 }
        test("also passes") { 3 shouldBe 3 }
        test("fails a check") { 2 shouldBe 3 }
        test("throws") { error("no database") }
        test("is skipped", ignore("not yet")) { 2 shouldBe 2 }
    }
}
