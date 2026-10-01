package io.github.matthewjones372.lark.test

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [block] as a story named [title], by default the calling test's name, and prints its steps when it ends.
 * A step that throws ends it with a [StoryFailed] whose message is the story up to that step, never coloured.
 *
 * The printed copy is coloured under `-Dlark.test.colour=always`, `FORCE_COLOR` or IntelliJ, and not under
 * `never` or `NO_COLOR`. A Gradle test worker does not see the shell's environment, so a build hands them on:
 * ```
 * tasks.test {
 *     listOf("FORCE_COLOR", "NO_COLOR").forEach { name ->
 *         providers.environmentVariable(name).orNull?.let { environment(name, it) }
 *     }
 *     providers.gradleProperty("lark.test.colour").orNull?.let { systemProperty("lark.test.colour", it) }
 * }
 * ```
 */
fun story(title: String = callingTest(), block: Story.() -> Unit) {
    val story = Story()
    try {
        story.block()
    } catch (failure: Throwable) {
        println(story.told(title, Colour.wanted))
        // Led by the failing step's own frame, so the first line a test runner links to is the assertion.
        throw StoryFailed(story.told(title, colour = false), failure)
            .apply { stackTrace = failure.fromTheTest().toTypedArray().ifEmpty { stackTrace } }
    }
    println(story.told(title, Colour.wanted))
}

/** A story that failed: the message is the transcript up to the failing step, and the cause what it threw. */
class StoryFailed(transcript: String, cause: Throwable) : AssertionError(transcript, cause)

// Given, When and Then are capitalised because `when` is a keyword, and the five read as one set.
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
class Story internal constructor() {

    private val steps = mutableListOf<Step>()
    private var depth = 0

    fun <A> Given(text: String, block: () -> A): A = step("Given", text, null, block)

    fun <A> When(text: String, block: () -> A): A = step("When", text, null, block)

    fun <A> Then(text: String, block: () -> A): A = step("Then", text, null, block)

    fun <A> And(text: String, block: () -> A): A = step("And", text, null, block)

    fun <A> But(text: String, block: () -> A): A = step("But", text, null, block)

    fun Given(text: String) = Waiting("Given", text)

    fun When(text: String) = Waiting("When", text)

    fun Then(text: String) = Waiting("Then", text)

    fun And(text: String) = Waiting("And", text)

    fun But(text: String) = Waiting("But", text)

    /** A step that waits for its block to stop throwing. */
    inner class Waiting internal constructor(private val keyword: String, private val text: String) {

        /** [eventually], as a step: its line shows the tries, and a [GaveUp] tells its last failure under it. */
        fun <A> eventually(within: Duration, every: Duration = 20.milliseconds, block: () -> A): A {
            val tries = Tries()
            // Named in full: inside this class, `eventually` is this member, and the step would call itself.
            return step(keyword, text, tries) {
                io.github.matthewjones372.lark.test.eventually(within, every) { tries.count++; block() }
            }
        }
    }

    private fun <A> step(keyword: String, text: String, tries: Tries?, block: () -> A): A {
        val step = Step(keyword, text, depth, tries)
        steps += step
        val started = System.nanoTime()
        depth++
        try {
            return block().also { step.outcome = Outcome.Passed }
        } catch (failure: Throwable) {
            // Only the innermost step that saw this failure tells it; the steps around it are marked failed.
            val seen = steps.any { it !== step && (it.outcome as? Outcome.Failed)?.failure === failure }
            step.outcome = Outcome.Failed(failure, told = !seen)
            throw failure
        } finally {
            depth--
            step.took = (System.nanoTime() - started).nanoseconds
        }
    }

    internal fun told(title: String, colour: Boolean): String {
        val ink = Ink(colour)
        val heads = steps.map { "  ".repeat(it.depth + 1) + "${it.mark} ${it.keyword} ${it.text}" }
        val width = heads.maxOfOrNull { it.length } ?: 0
        return buildString {
            append(ink.bold("Story: $title"))
            steps.zip(heads).forEach { (step, head) ->
                val padded = head.padEnd(width + GAP)
                val failed = step.outcome as? Outcome.Failed
                append('\n')
                append(if (failed != null) ink.red(ink.bold(padded)) else padded.replaceFirst("✓", ink.green("✓")))
                append(ink.dim(step.timing()))
                if (failed != null && failed.told) {
                    val indent = "  ".repeat(step.depth + 3)
                    failed.said().lines().forEach { append('\n').append(ink.red(indent + it)) }
                    // A frame printed as a stack trace prints one, which an IDE's console turns into a link.
                    failed.failure.fromTheTest().firstOrNull()?.let { append('\n').append(ink.dim("${indent}at $it")) }
                }
            }
        }
    }

    private class Tries {
        var count = 0
    }

    private class Step(val keyword: String, val text: String, val depth: Int, val tries: Tries?) {
        var outcome: Outcome? = null
        var took: Duration = Duration.ZERO

        val mark: String get() = if (outcome is Outcome.Failed) "✗" else "✓"

        fun timing(): String {
            val time = if (took <
                1.seconds
            ) "${took.inWholeMilliseconds} ms" else "%.1f s".format(took.inWholeMilliseconds / MILLIS)
            val count = tries?.count ?: 0
            return if (count > 1) "$time, $count tries" else time
        }
    }

    private sealed interface Outcome {
        data object Passed : Outcome

        class Failed(val failure: Throwable, val told: Boolean) : Outcome {
            /** What went wrong, in its own words: a [GaveUp] tells its last failure, not that it gave up. */
            fun said(): String {
                val what = if (failure is GaveUp) failure.cause ?: failure else failure
                return what.message ?: what.toString()
            }
        }
    }

    private companion object {
        const val GAP = 4
        const val MILLIS = 1000.0
    }
}

/** The first frame outside this file, which for a JUnit test is the test method and its backticked name. */
private fun callingTest(): String =
    StackWalker.getInstance().walk { frames ->
        frames.filter { it.className != "io.github.matthewjones372.lark.test.StoryKt" }
            .findFirst()
            .map { it.methodName }
            .orElse("a story")
    }

/**
 * Where [this] was thrown in the test's own code: its frames from the first that is not the story's or
 * `eventually`'s, lark's core, an assertion library's, Kotlin's, Arrow's or the JDK's. A [GaveUp] answers for
 * its last failure.
 */
internal fun Throwable.fromTheTest(): List<StackTraceElement> {
    val thrown = if (this is GaveUp) cause ?: this else this
    return thrown.stackTrace.dropWhile { frame ->
        frame.className.substringBefore('$') in ours ||
            frame.className.substringBeforeLast('.') == LARK ||
            libraries.any { frame.className.startsWith(it) }
    }
}

private const val LARK = "io.github.matthewjones372.lark"

// Not every class under lark's prefix: lark-test's own tests live there, and must point at themselves.
private val ours = setOf("$LARK.test.StoryKt", "$LARK.test.Story", "$LARK.test.EventuallyKt")

private val libraries = listOf("io.kotest.", "org.opentest4j.", "kotlin.", "java.", "jdk.", "sun.", "arrow.")
