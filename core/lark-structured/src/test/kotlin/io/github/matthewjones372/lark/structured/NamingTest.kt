package io.github.matthewjones372.lark.structured

import arrow.core.raise.either
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class NamingTest {

    @Test
    fun `a branch's thread is virtual and named after the function that forked it`() {
        val (name, virtual) = either<Nothing, Pair<String, Boolean>> {
            parZip({ Thread.currentThread().run { name to isVirtual } }, { 0 }) { a, _ -> a }
        }.getOrNull()!!
        name shouldBe "NamingTest.a branch's thread is virtual and named after the function that forked it/1"
        virtual shouldBe true
    }

    @Test
    fun `a combinator inside a branch is named by the branch's path`() {
        val inner = either<Nothing, String> {
            parZip({ 0 }, {
                parZip({ Thread.currentThread().name }, { 0 }) { a, _ -> a }
            }) { _, b -> b }
        }.getOrNull()!!
        inner shouldStartWith "NamingTest.a combinator inside a branch"
        inner shouldEndWith "/2/1"
    }
}
