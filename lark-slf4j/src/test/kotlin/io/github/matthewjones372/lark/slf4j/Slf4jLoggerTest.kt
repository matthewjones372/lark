package io.github.matthewjones372.lark.slf4j

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logDebug
import io.github.matthewjones372.lark.logError
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.logger
import io.github.matthewjones372.lark.parMap
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * What a line looks like once it reaches a backend, asserted against logback rather than a fake.
 *
 * The appender is attached to the name the adapter logs under, because a service configures a
 * backend by name and a test that bypassed the name would not notice the adapter dropping it.
 */
class Slf4jLoggerTest {

    private val appender = ListAppender<ILoggingEvent>()

    private val backend = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("lark")

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
    fun `the name a service configures is the name it was given`() {
        val named = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("petshop")
        val its = ListAppender<ILoggingEvent>().also { it.start() }
        named.addAppender(its)

        logger.locally(Slf4jLogger("petshop")) { logInfo("named") }

        its.list.single().message shouldBe "named"
        named.detachAppender(its)
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
