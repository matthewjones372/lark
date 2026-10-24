package io.github.matthewjones372.lark.junit.samples

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.story
import io.github.matthewjones372.lark.resource

/** Untitled stories, each test named for how its story should end. */
object StorySuite : Suite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    init {
        test("passes its story") { two ->
            story {
                val four = When("a passing story doubles two") { two * 2 }
                Then("it has four") { four shouldBe 4 }
            }
        }
        test("fails two thens") { two ->
            story {
                Given("two") { }
                Then("it is three") { two shouldBe 3 } and
                    Then("it is four") { two shouldBe 4 }
            }
        }
        test("fails one then of two") { two ->
            story {
                Then("it is two") { two shouldBe 2 } and
                    Then("it is five") { two shouldBe 5 }
            }
        }
        test("throws in a step") { _ ->
            story {
                Given("a step that throws") { error("no database") }
            }
        }
    }
}
