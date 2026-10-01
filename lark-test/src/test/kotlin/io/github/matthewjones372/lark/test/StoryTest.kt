package io.github.matthewjones372.lark.test

import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StoryTest {

    @Test
    fun `a value one step answers is what the next step is given`() = story {
        val pets = Given("a shop with three pets") { listOf("Nibbles", "Barnaby", "Mrs Peel") }
        val taken = When("Ada adopts the first") { pets.first() }
        Then("Ada has Nibbles") { taken shouldBe "Nibbles" }
    }

    @Test
    fun `a story failing at its third step says the first two passed and what the third said`() {
        val failed = shouldThrow<StoryFailed> {
            story("adopting") {
                Given("a shop") { }
                When("Ada adopts Nibbles") { }
                Then("Nibbles is hers") { "Bea" shouldBe "Ada" }
                And("never reached") { }
            }
        }

        val lines = failed.message!!.lines()
        lines[0] shouldBe "Story: adopting"
        lines[1] shouldContain "✓ Given a shop"
        lines[2] shouldContain "✓ When Ada adopts Nibbles"
        lines[3] shouldContain "✗ Then Nibbles is hers"
        lines[4] shouldContain "expected:<Ada> but was:<Bea>"
        failed.message!! shouldNotContain "never reached"
        failed.cause!!.message shouldContain "expected:<Ada> but was:<Bea>"
    }

    @Test
    fun `a failure is told once, under the innermost step that saw it`() {
        val failed = shouldThrow<StoryFailed> {
            story("nested") {
                Given("the service") { And("its database") { error("refused") } }
            }
        }

        val lines = failed.message!!.lines()
        lines[1] shouldContain "✗ Given the service"
        lines[2] shouldContain "    ✗ And its database"
        lines.count { "refused" in it } shouldBe 1
    }

    @Test
    fun `but is a step too`() = story {
        But("it answers like any other") { 1 } shouldBe 1
    }

    @Test
    fun `a waiting step answers once its block stops throwing, and says how many tries it took`() {
        val asked = AtomicInteger()
        val failed = shouldThrow<StoryFailed> {
            story("waiting") {
                Then("it gets there").eventually(within = 1.seconds, every = 1.milliseconds) {
                    check(asked.incrementAndGet() >= 3) { "not yet" }
                }
                And("this fails, to see the transcript") { error("stop") }
            }
        }

        failed.message!!.lines()[1] shouldContain "3 tries"
    }

    @Test
    fun `a waiting step that runs out of time shows its tries, and the last failure under it`() {
        val moving = TestClock()
        val last = AssertionError("expected:<0L> but was:<3L>")

        val failed = clock.locally(moving) {
            flock<Nothing, StoryFailed> {
                val telling = async {
                    shouldThrow<StoryFailed> {
                        story("draining") {
                            And("the outbox drains").eventually(5.seconds, every = 1.seconds) { throw last }
                        }
                    }
                }
                repeat(5) { moving.adjustWhenBlocked(1.seconds) }
                telling.await()
            }
        }.getOrNull()!!

        val lines = failed.message!!.lines()
        lines[1] shouldContain "✗ And the outbox drains"
        lines[1] shouldContain "6 tries"
        lines[2].trim() shouldBe "expected:<0L> but was:<3L>"
        failed.cause.shouldBeInstanceOf<GaveUp>().cause shouldBeSameInstanceAs last
    }

    @Test
    fun `the story is printed when it ends, plain`() {
        val printed = ByteArrayOutputStream()
        val console = System.out
        System.setOut(PrintStream(printed, true, Charsets.UTF_8))
        try {
            story("printed") { Given("a step") { } }
        } finally {
            System.setOut(console)
        }

        val out = printed.toString(Charsets.UTF_8)
        out shouldContain "Story: printed"
        out shouldContain "✓ Given a step"
        out shouldNotContain "\u001B["
    }

    @Test
    fun `a story takes its title from the test that tells it`() {
        val failed = shouldThrow<StoryFailed> { story { Then("it fails") { error("no") } } }

        failed.message!!.lines()[0] shouldBe "Story: a story takes its title from the test that tells it"
    }
}
