package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldHaveSize
import io.github.matthewjones372.lark.logger
import io.github.matthewjones372.lark.resource
import java.sql.SQLException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private fun failing(message: String) = SQLException(message)

object UncaughtSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("what no thread caught is an error in the log, with its stack, not a trace on stderr") { _ ->
            val lines = mutableListOf<LogLine>()
            val thrown = failing("the database went away")
            logger.locally(Logger { lines.add(it) }) { Uncaught().uncaughtException(Thread.currentThread(), thrown) }

            (lines shouldHaveSize 1) and
                (lines.singleOrNull()?.level shouldBe LogLevel.Error) and
                (lines.singleOrNull()?.message shouldContain "the database went away") and
                (lines.singleOrNull()?.cause shouldBe thrown)
        }

        test(
            "the same failure again within the window is counted, and the count said with the next one after it",
        ) { _ ->
            val lines = mutableListOf<LogLine>()
            var now = 0L
            val uncaught = Uncaught(window = 1.minutes) { now }
            logger.locally(Logger { lines.add(it) }) {
                repeat(1_000) { uncaught.uncaughtException(Thread.currentThread(), failing("down")) }
                now += 61.seconds.inWholeNanoseconds
                uncaught.uncaughtException(Thread.currentThread(), failing("down"))
            }

            (lines shouldHaveSize 2) and (lines.lastOrNull()?.message shouldContain "999 more like it")
        }

        test("a different failure is logged at once, whatever the other did") { _ ->
            val lines = mutableListOf<LogLine>()
            val uncaught = Uncaught(window = 1.minutes) { 0L }
            logger.locally(Logger { lines.add(it) }) {
                uncaught.uncaughtException(Thread.currentThread(), failing("down"))
                uncaught.uncaughtException(Thread.currentThread(), IllegalStateException("a bug"))
            }

            lines shouldHaveSize 2
        }
    }
}
