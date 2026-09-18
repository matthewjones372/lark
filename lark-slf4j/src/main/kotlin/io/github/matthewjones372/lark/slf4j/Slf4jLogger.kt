package io.github.matthewjones372.lark.slf4j

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * lark's lines, handed to whichever SLF4J backend the service has already configured.
 *
 * ```kotlin
 * val exit = logger.locally(Slf4jLogger()) { runApp(app) }
 * ```
 *
 * The annotations go into the MDC rather than onto the end of the message. `logAnnotated` binds a
 * pair that is carried across a fork, which an MDC cannot do by itself, and appending it to the text
 * would leave `%X{pet_id}`, a JSON encoder and every field search with nothing to read.
 *
 * [name] is the SLF4J logger to write to, because a backend is configured by name and a service
 * wants a level it can set for its own lines without setting it for its libraries'.
 */
class Slf4jLogger(name: String = "lark") : Logger {

    private val log = LoggerFactory.getLogger(name)

    override fun log(line: LogLine) {
        // Put back rather than cleared. The thread is one a pool may hand to something else next, so
        // nothing may carry over — and a key the service set itself is not this adapter's to drop.
        val before = MDC.getCopyOfContextMap()
        line.annotations.forEach { (key, value) -> MDC.put(key, value) }
        try {
            say(line)
        } finally {
            if (before == null) MDC.clear() else MDC.setContextMap(before)
        }
    }

    private fun say(line: LogLine) = when (line.level) {
        LogLevel.Debug -> log.debug(line.message, line.cause)
        LogLevel.Info -> log.info(line.message, line.cause)
        LogLevel.Warn -> log.warn(line.message, line.cause)
        LogLevel.Error -> log.error(line.message, line.cause)
    }
}
