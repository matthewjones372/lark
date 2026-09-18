package io.github.matthewjones372.lark.slf4j

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import org.slf4j.LoggerFactory

/**
 * lark's lines, handed to whichever SLF4J backend the service has already configured.
 *
 * ```kotlin
 * val exit = logger.locally(Slf4jLogger()) { runApp(app) }
 * ```
 *
 * [name] is the SLF4J logger to write to, because a backend is configured by name and a service
 * wants a level it can set for its own lines without setting it for its libraries'.
 */
class Slf4jLogger(name: String = "lark") : Logger {

    private val log = LoggerFactory.getLogger(name)

    override fun log(line: LogLine) = when (line.level) {
        LogLevel.Debug -> log.debug(line.message, line.cause)
        LogLevel.Info -> log.info(line.message, line.cause)
        LogLevel.Warn -> log.warn(line.message, line.cause)
        LogLevel.Error -> log.error(line.message, line.cause)
    }
}
