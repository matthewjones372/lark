package io.github.matthewjones372.lark.test

import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(StorySeam::class)
class StoryLocalsTest {

    private fun printedBy(block: () -> Unit): String {
        val printed = ByteArrayOutputStream()
        val console = System.out
        System.setOut(PrintStream(printed, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(console)
        }
        return printed.toString(Charsets.UTF_8)
    }

    @Test
    fun `an untitled story takes the bound title and tells its transcript where it is bound`() {
        val told = CopyOnWriteArrayList<String>()

        val printed = printedBy {
            storyTitle.locally("a bound title") {
                storyTranscript.locally({ told += it }) {
                    story { Given("a step") { } }
                }
            }
        }

        told.single().lines().first() shouldBe "Story: a bound title"
        printed shouldNotContain "a bound title"
    }

    @Test
    fun `a failing story tells its transcript where it is bound before it throws`() {
        val told = CopyOnWriteArrayList<String>()

        val failed = storyTranscript.locally({ told += it }) {
            shouldThrow<StoryFailed> { story("failing") { Then("it fails") { error("no") } } }
        }

        told.single() shouldBe failed.message
    }

    @Test
    fun `a title given to the story wins over the bound one`() {
        val told = CopyOnWriteArrayList<String>()

        storyTitle.locally("the bound title") {
            storyTranscript.locally({ told += it }) { story("its own title") { } }
        }

        told.single() shouldBe "Story: its own title"
    }

    @Test
    fun `a story told in a fork sees what its opener bound`() {
        val told = CopyOnWriteArrayList<String>()

        storyTitle.locally("from the opener") {
            storyTranscript.locally({ told += it }) {
                flock<Nothing, Unit> { async { story { } } }
            }
        }

        told.single() shouldBe "Story: from the opener"
    }
}
