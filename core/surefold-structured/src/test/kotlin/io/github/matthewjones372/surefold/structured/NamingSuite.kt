package io.github.matthewjones372.surefold.structured

import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.junit.Expectations
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldEndWith
import io.github.matthewjones372.surefold.junit.shouldStartWith

object NamingSuite : Suite() {
    init {
        test("a branch's thread is virtual and named after the function that forked it") {
            `a branch's thread is virtual and named after the function that forked it`()
        }
        test("a combinator inside a branch is named by the branch's path") {
            `a combinator inside a branch is named by the branch's path`()
        }
    }

    // A scope is named after the function that opened it, and a test's lambda has no name of its own: each test
    // forks from a function named as the test is, as a Jupiter method was, so the name is what the check reads.
    @Suppress("ktlint:standard:function-naming")
    private fun `a branch's thread is virtual and named after the function that forked it`(): Expectations {
        val (name, virtual) = attempt<Nothing, Pair<String, Boolean>> {
            parZip({ Thread.currentThread().run { name to isVirtual } }, { 0 }) { a, _ -> a }
        }.getOrNull()!!
        return (
            name shouldBe "NamingSuite.a branch's thread is virtual and named after the function that forked it/1"
            ) and (virtual shouldBe true)
    }

    @Suppress("ktlint:standard:function-naming")
    private fun `a combinator inside a branch is named by the branch's path`(): Expectations {
        val inner = attempt<Nothing, String> {
            parZip({ 0 }, {
                parZip({ Thread.currentThread().name }, { 0 }) { a, _ -> a }
            }) { _, b -> b }
        }.getOrNull()!!
        return (inner shouldStartWith "NamingSuite.a combinator inside a branch") and (inner shouldEndWith "/2/1")
    }
}
