package io.github.matthewjones372.lark.structured

import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class JoinersTest {

    private data object Boom

    @Test
    fun `parAll answers every branch in order`() {
        either<Nothing, List<Int>> { parAll("all", { 1 }, { 2 }, { 3 }) } shouldBe listOf(1, 2, 3).right()
    }

    @Test
    fun `the first branch to fail cancels the others and is the answer`() {
        val blocker = Blocker()
        either<Boom, List<String>> {
            parAll("fails", { blocker.body() }, {
                blocker.awaitStart()
                raise(Boom)
            })
        } shouldBe Boom.left()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `firstOf answers with the first to finish and cancels the rest`() {
        val blocker = Blocker()
        either<Nothing, String> {
            firstOf("race", { blocker.body() }, {
                blocker.awaitStart()
                "fast"
            })
        } shouldBe "fast".right()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `a branch that raises first loses the race for everyone`() {
        val blocker = Blocker()
        either<Boom, String> {
            firstOf("race", { blocker.body() }, {
                blocker.awaitStart()
                raise(Boom)
            })
        } shouldBe Boom.left()
    }
}
