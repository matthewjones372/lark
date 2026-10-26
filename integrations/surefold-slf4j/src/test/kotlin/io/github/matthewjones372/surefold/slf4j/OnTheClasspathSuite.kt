package io.github.matthewjones372.surefold.slf4j

import io.github.matthewjones372.surefold.Logger
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeInstanceOf
import io.github.matthewjones372.surefold.junit.withClue
import io.github.matthewjones372.surefold.logger
import java.util.ServiceLoader
import java.util.concurrent.FutureTask

/** What [read] answers on a new thread, where nothing is bound: a suite's test runs under a capture of its own. */
private fun <A> unbound(read: () -> A): A = FutureTask(read).also { Thread.ofVirtual().start(it) }.get()

/**
 * The whole of the wiring: this jar is on the classpath, so this is where a line goes.
 *
 * The service file is what a consumer never reads and every consumer depends on, so it is asserted
 * rather than assumed — a typo in it is a silent return to stderr.
 */
object OnTheClasspathSuite : Suite() {
    init {
        test("nothing is bound, and a line still goes to the backend") {
            withClue("no logger.locally anywhere above this line, which is the point of the module") {
                unbound { logger.get() }.shouldBeInstanceOf<Slf4jLogger>()
            }
        }

        test("the service file names a class that exists and takes no arguments") {
            val found = ServiceLoader.load(Logger::class.java, Logger::class.java.classLoader).toList()

            withClue("ServiceLoader needs a no-argument constructor; every parameter having a default is") {
                found.map { it::class } shouldBe listOf(Slf4jLogger::class)
            }
        }
    }
}
