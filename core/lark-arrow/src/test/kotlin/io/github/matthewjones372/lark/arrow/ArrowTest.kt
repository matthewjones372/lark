package io.github.matthewjones372.lark.arrow

import arrow.core.raise.either
import arrow.core.raise.ensure
import io.github.matthewjones372.lark.fail.attempt
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.nonEmptyListOf
import io.github.matthewjones372.lark.fail.right
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import arrow.core.left as arrowLeft
import arrow.core.nonEmptyListOf as arrowNonEmptyListOf
import arrow.core.right as arrowRight

class ArrowTest {

    @Test
    fun `an Either keeps its side and value across the conversion and back`() {
        1.right().toArrow() shouldBe 1.arrowRight()
        "e".left().toArrow() shouldBe "e".arrowLeft()
        1.arrowRight().toLark() shouldBe 1.right()
        "e".arrowLeft().toLark() shouldBe "e".left()
    }

    @Test
    fun `a NonEmptyList keeps its elements in order across the conversion and back`() {
        nonEmptyListOf(1, 2, 3).toArrow() shouldBe arrowNonEmptyListOf(1, 2, 3)
        arrowNonEmptyListOf(1, 2, 3).toLark() shouldBe nonEmptyListOf(1, 2, 3)
        arrowNonEmptyListOf("only").toLark() shouldBe nonEmptyListOf("only")
    }

    @Test
    fun `a fail inside lark raises into the Arrow scope around it`() {
        either<String, Int> { lark { 1 } } shouldBe 1.arrowRight()
        either<String, Int> { lark { fail("lark") } } shouldBe "lark".arrowLeft()
    }

    @Test
    fun `a raise inside arrow fails the Lark scope around it`() {
        attempt<String, Int> { arrow { 2 } } shouldBe 2.right()
        attempt<String, Int> {
            arrow {
                ensure(false) { "arrow" }
                0
            }
        } shouldBe "arrow".left()
    }
}
