package io.github.matthewjones372.lark

import java.time.Instant

enum class LogLevel { Debug, Info, Warn, Error }

/** One line, as a value: what a backend renders and what a test asserts on. */
data class LogLine(
    val level: LogLevel,
    val message: String,
    val at: Instant,
    val cause: Throwable? = null,
)

fun interface Logger {
    fun log(line: LogLine)
}

/** Enough to see a service start. Anything more is an adapter's, and a dependency this module has not got. */
object StderrLogger : Logger {

    override fun log(line: LogLine) {
        System.err.println("${line.at} ${line.level.name.uppercase()} ${line.message}")
        line.cause?.printStackTrace(System.err)
    }
}

/** The logger a line goes to, and the one a fork inherits from its opener. */
val logger: LarkLocal<Logger> = larkLocal { StderrLogger }

fun logDebug(message: String) = log(LogLevel.Debug, message, null)

fun logInfo(message: String) = log(LogLevel.Info, message, null)

fun logWarn(message: String) = log(LogLevel.Warn, message, null)

fun logError(message: String, cause: Throwable? = null) = log(LogLevel.Error, message, cause)

private fun log(level: LogLevel, message: String, cause: Throwable?) =
    logger.get().log(LogLine(level, message, clock.get().now(), cause))
