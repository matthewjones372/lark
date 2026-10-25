package io.github.matthewjones372.lark.arrow

import arrow.core.raise.either
import arrow.core.raise.ensure
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.fail.attempt
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.nonEmptyListOf
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.resource
import arrow.core.left as arrowLeft
import arrow.core.nonEmptyListOf as arrowNonEmptyListOf
import arrow.core.right as arrowRight

object ArrowSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("an Either keeps its side and value across the conversion and back") {
            (1.right().toArrow() shouldBe 1.arrowRight()) and
                ("e".left().toArrow() shouldBe "e".arrowLeft()) and
                (1.arrowRight().toLark() shouldBe 1.right()) and
                ("e".arrowLeft().toLark() shouldBe "e".left())
        }

        test("a NonEmptyList keeps its elements in order across the conversion and back") {
            (nonEmptyListOf(1, 2, 3).toArrow() shouldBe arrowNonEmptyListOf(1, 2, 3)) and
                (arrowNonEmptyListOf(1, 2, 3).toLark() shouldBe nonEmptyListOf(1, 2, 3)) and
                (arrowNonEmptyListOf("only").toLark() shouldBe nonEmptyListOf("only"))
        }

        test("a fail inside lark raises into the Arrow scope around it") {
            (either<String, Int> { lark { 1 } } shouldBe 1.arrowRight()) and
                (either<String, Int> { lark { fail("lark") } } shouldBe "lark".arrowLeft())
        }

        test("a raise inside arrow fails the Lark scope around it") {
            val raised = attempt<String, Int> {
                arrow {
                    ensure(false) { "arrow" }
                    0
                }
            }
            (attempt<String, Int> { arrow { 2 } } shouldBe 2.right()) and (raised shouldBe "arrow".left())
        }
    }
}
