package io.github.matthewjones372.lark

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.Collections

class LoggerTest {

    private class Recorded : Logger {
        val lines: MutableList<LogLine> = Collections.synchronizedList(mutableListOf())

        override fun log(line: LogLine) {
            lines += line
        }
    }

    @Test
    fun `a line carries its level and its message`() {
        val recorded = Recorded()

        logger.locally(recorded) {
            logDebug("looking")
            logInfo("found")
            logWarn("slow")
            logError("broken", IllegalStateException("why"))
        }

        recorded.lines.map { it.level } shouldContainExactly
            listOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error)
        recorded.lines.map { it.message } shouldContainExactly listOf("looking", "found", "slow", "broken")
        recorded.lines.last().cause.shouldNotBeNull().message shouldBe "why"
    }

    @Test
    fun `a line logged inside a parMap branch reaches the bound logger`() {
        val recorded = Recorded()

        logger.locally(recorded) { parMap(listOf(1, 2, 3)) { logInfo("branch $it") } }

        recorded.lines shouldHaveSize 3
        recorded.lines.map { it.message }.toSet() shouldBe setOf("branch 1", "branch 2", "branch 3")
    }

    @Test
    fun `a line is stamped by the clock the thread inherited`() {
        val at = Instant.parse("2026-09-10T00:00:00Z")
        val recorded = Recorded()

        clock.locally(fixedClock(at)) { logger.locally(recorded) { logInfo("stamped") } }

        recorded.lines.single().at shouldBe at
    }

    @Test
    fun `nothing bound writes to stderr`() {
        val captured = ByteArrayOutputStream()
        val was = System.err

        try {
            System.setErr(PrintStream(captured, true))
            logInfo("to whoever is listening")
        } finally {
            System.setErr(was)
        }

        captured.toString() shouldContain "to whoever is listening"
        captured.toString() shouldContain "INFO"
    }
}
