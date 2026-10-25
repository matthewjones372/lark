@file:MustUseReturnValues

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
 * surefold-test's `story`, as a suite's test body: it returns what its block expects, titled by default with the test's
 * name. A step whose value is a failed [Expectations] is marked failed and the story goes on; a step that throws
 * still ends it.
 */
@OptIn(StorySeam::class)
fun story(title: String = storyTitle.get() ?: "a story", block: Story.() -> Expectations): Expectations =
    tellStory(title, { (it as? Expectations)?.failure }, block)

/** Runs [block] with its stories titled [test] and their transcripts logged, so they are reported with its logs. */
@OptIn(StorySeam::class)
internal fun <A> tellingStories(test: String, block: () -> A): A = storyTitle.locally(test) {
    storyTranscript.locally({ logger.get().log(LogLine(LogLevel.Info, it, clock.get().now())) }, block)
}
