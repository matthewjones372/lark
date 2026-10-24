package io.github.matthewjones372.lark.slf4j

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeNull
import io.github.matthewjones372.lark.assert.withClue
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logDebug
import io.github.matthewjones372.lark.logError
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.logger
import io.github.matthewjones372.lark.parMap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Instant

/**
 * What a line looks like once it reaches a backend, asserted against logback rather than a fake.
 *
 * The appender is attached to the name the adapter logs under, this class's own, because a service
 * configures a backend by name and a test that bypassed the name would not notice the adapter dropping it.
 */
class Slf4jLoggerTest {

    private val appender = ListAppender<ILoggingEvent>()

    private val backend = backendNamed(Slf4jLoggerTest::class.java.name)

    @BeforeEach
    fun attach() {
        appender.start()
        backend.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        backend.detachAppender(appender)
        appender.stop()
    }

    private fun <A> logging(block: () -> A): A = logger.locally(Slf4jLogger(), block)

    private fun backendNamed(name: String) = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(name)

    private fun <A> capturing(name: String, block: () -> A): List<ILoggingEvent> {
        val named = backendNamed(name)
        val its = ListAppender<ILoggingEvent>().also { it.start() }
        named.addAppender(its)
        try {
            block()
        } finally {
            named.detachAppender(its)
        }
        return its.list
    }

    @Test
    fun `each level reaches the call the backend names it by`() {
        logging {
            logDebug("debug")
            logInfo("info")
            logWarn("warn")
            logError("error")
        }

        appender.list.map { it.level to it.message } shouldBe listOf(
            Level.DEBUG to "debug",
            Level.INFO to "info",
            Level.WARN to "warn",
            Level.ERROR to "error",
        )
    }

    @Test
    fun `a message arrives as it was written, with nothing appended to it`() {
        logging { logInfo("one tortoise, adopted") }

        appender.list.single().message shouldBe "one tortoise, adopted"
    }

    @Test
    fun `a cause is a throwable to the backend, not a line of text`() {
        val boom = IllegalStateException("no tortoises")

        logging { logError("adoption failed", boom) }

        withClue("a stack trace rendered into the message is one no backend can group by") {
            appender.list.single().throwableProxy.message shouldBe "no tortoises"
        }
    }

    @Test
    fun `a line reaches the logger named after the class that wrote it`() {
        val lines = capturing(Registry::class.java.name) { logging { Registry().register("tortoise-1") } }

        withClue("a backend configured by package reads this name, not `lark`") {
            lines.map { it.loggerName to it.message } shouldBe listOf(
                Registry::class.java.name to "registering tortoise-1",
            )
        }
    }

    @Test
    fun `the name a service configures is the name it was given`() {
        val lines = capturing("app") { logger.locally(Slf4jLogger("app")) { logInfo("named") } }

        lines.map { it.loggerName to it.message } shouldBe listOf("app" to "named")
        appender.list.shouldBe(emptyList())
    }

    @Test
    fun `a line with no origin reaches lark`() {
        val lines = capturing("lark") { Slf4jLogger().log(LogLine(LogLevel.Info, "unplaced", Instant.EPOCH)) }

        lines.map { it.message } shouldBe listOf("unplaced")
    }

    @Test
    fun `an annotation is in the MDC while its block runs, and gone afterwards`() {
        logging {
            logAnnotated("pet_id" to "tortoise-1") { logInfo("inside") }
            logInfo("outside")
        }

        val (inside, outside) = appender.list

        withClue("a pattern of %X{pet_id} and a field search both read this and not the message") {
            inside.mdcPropertyMap["pet_id"] shouldBe "tortoise-1"
        }
        withClue("a pooled thread hands itself to something else next, and must carry nothing over") {
            outside.mdcPropertyMap["pet_id"].shouldBeNull()
        }
    }

    @Test
    fun `a line written on a fork carries what its opener bound`() {
        logging {
            logAnnotated("pet_id" to "tortoise-1") {
                parMap(listOf(1, 2)) { logInfo("fork $it") }
            }
        }

        withClue("the claim an MDC cannot make by itself, and the reason this adapter exists") {
            appender.list.map { it.mdcPropertyMap["pet_id"] } shouldBe listOf("tortoise-1", "tortoise-1")
        }
    }

    @Test
    fun `a key the service set outside lark is still there afterwards`() {
        MDC.put("service", "petshop")

        logging { logAnnotated("pet_id" to "tortoise-1") { logInfo("both") } }

        withClue("put back rather than cleared: what lark did not set is not lark's to drop") {
            MDC.get("service") shouldBe "petshop"
        }
        appender.list.single().mdcPropertyMap["service"] shouldBe "petshop"
        MDC.clear()
    }

    @Test
    fun `every level lark has is one this knows`() {
        withClue("a `when` over LogLevel has no else, so a new level is a compiler error here") {
            LogLevel.entries.size shouldBe 4
        }
    }
}

private class Registry {
    fun register(id: String) = logInfo("registering $id")
}
