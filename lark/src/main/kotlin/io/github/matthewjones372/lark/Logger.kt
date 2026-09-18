package io.github.matthewjones372.lark

import java.time.Instant
import java.util.ServiceLoader

enum class LogLevel { Debug, Info, Warn, Error }

/** One line, as a value: what a backend renders and what a test asserts on. */
data class LogLine(
    val level: LogLevel,
    val message: String,
    val at: Instant,
    val cause: Throwable? = null,
    val annotations: Map<String, String> = emptyMap(),
)

fun interface Logger {
    fun log(line: LogLine)
}

/** Enough to see a service start. Anything more is an adapter's, and a dependency this module has not got. */
object StderrLogger : Logger {

    override fun log(line: LogLine) {
        val annotated = line.annotations.entries.joinToString("") { (key, value) -> " $key=$value" }
        System.err.println("${line.at} ${line.level.name.uppercase()} ${line.message}$annotated")
        line.cause?.printStackTrace(System.err)
    }
}

/** The logger a line goes to, and the one a fork inherits from its opener. */
val logger: LarkLocal<Logger> = larkLocal { discovered }

/**
 * The [Logger] a jar on the classpath registered, or [StderrLogger].
 *
 * So an adapter is a dependency and not a line in `main`: `lark-slf4j` ships a service file, and a
 * service that puts it on the classpath has bound nothing and still logs where everything else does.
 * `logger.locally` overrides it, which is what a test does and what an application does where it
 * builds its own.
 *
 * Resolved once, because the initial value of a [LarkLocal] is asked for on every unbound read, and
 * a `ServiceLoader` scan per log line is a scan per log line. Loaded through this class's own loader
 * rather than the thread's: which thread a line is written on is exactly what lark makes vary.
 */
private val discovered: Logger by lazy {
    ServiceLoader.load(Logger::class.java, Logger::class.java.classLoader).firstOrNull() ?: StderrLogger
}

fun logDebug(message: String) = log(LogLevel.Debug, message, null)

fun logInfo(message: String) = log(LogLevel.Info, message, null)

fun logWarn(message: String) = log(LogLevel.Warn, message, null)

fun logError(message: String, cause: Throwable? = null) = log(LogLevel.Error, message, cause)

private fun log(level: LogLevel, message: String, cause: Throwable?) {
    val at = clock.get().now()
    logger.get().log(LogLine(level, message, at, cause, annotations.get() + spans.get().elapsedAt(at)))
}
