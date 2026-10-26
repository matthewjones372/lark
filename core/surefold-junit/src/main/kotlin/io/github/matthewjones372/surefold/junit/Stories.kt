package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.LogLevel
import io.github.matthewjones372.surefold.LogLine
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.logger
import io.github.matthewjones372.surefold.test.Story
import io.github.matthewjones372.surefold.test.StorySeam
import io.github.matthewjones372.surefold.test.storyTitle
import io.github.matthewjones372.surefold.test.storyTranscript
import io.github.matthewjones372.surefold.test.tellStory

/**
 * surefold-test's `story`, as a suite's test body, titled by default with the test's name. The checks its steps make
 * are recorded as the test's; a step whose checks failed is marked failed and the story goes on, and a step that
 * throws still ends it.
 */
@OptIn(StorySeam::class)
fun story(title: String = storyTitle.get() ?: "a story", block: Story.() -> Unit) {
    val into = record.get()
    var seen = into?.size ?: 0
    val failedStep: (Any?) -> Throwable? = { _ ->
        into?.let { it.since(seen).also { _ -> seen = it.size }.failure }
    }
    tellStory(title, failedStep, block)
}

/** Runs [block] with its stories titled [test] and their transcripts logged, so they are reported with its logs. */
@OptIn(StorySeam::class)
internal fun <A> tellingStories(test: String, block: () -> A): A = storyTitle.locally(test) {
    storyTranscript.locally({ logger.get().log(LogLine(LogLevel.Info, it, clock.get().now())) }, block)
}
