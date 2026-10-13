package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import io.github.matthewjones372.lark.logger
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class UncaughtTest {

    private fun failing(message: String) = SQLException(message)

    @Test
    fun `what no thread caught is an error in the log, with its stack, not a trace on stderr`() {
        val lines = mutableListOf<LogLine>()
        val thrown = failing("the database went away")
        logger.locally(Logger { lines.add(it) }) { Uncaught().uncaughtException(Thread.currentThread(), thrown) }

        lines shouldHaveSize 1
        lines.single().level shouldBe LogLevel.Error
        lines.single().message shouldContain "the database went away"
        lines.single().cause shouldBe thrown
    }

    @Test
    fun `the same failure again within the window is counted, and the count said with the next one after it`() {
        val lines = mutableListOf<LogLine>()
        var now = 0L
        val uncaught = Uncaught(window = 1.minutes) { now }
        logger.locally(Logger { lines.add(it) }) {
            repeat(1_000) { uncaught.uncaughtException(Thread.currentThread(), failing("down")) }
            now += 61.seconds.inWholeNanoseconds
            uncaught.uncaughtException(Thread.currentThread(), failing("down"))
        }

        lines shouldHaveSize 2
        lines.last().message shouldContain "999 more like it"
    }

    @Test
    fun `a different failure is logged at once, whatever the other did`() {
        val lines = mutableListOf<LogLine>()
        val uncaught = Uncaught(window = 1.minutes) { 0L }
        logger.locally(Logger { lines.add(it) }) {
            uncaught.uncaughtException(Thread.currentThread(), failing("down"))
            uncaught.uncaughtException(Thread.currentThread(), IllegalStateException("a bug"))
        }

        lines shouldHaveSize 2
    }
}
