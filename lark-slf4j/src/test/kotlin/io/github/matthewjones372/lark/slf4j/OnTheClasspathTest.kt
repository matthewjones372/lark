package io.github.matthewjones372.lark.slf4j

import io.github.matthewjones372.lark.Logger
import io.github.matthewjones372.lark.logger
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/**
 * The whole of the wiring: this jar is on the classpath, so this is where a line goes.
 *
 * The service file is what a consumer never reads and every consumer depends on, so it is asserted
 * rather than assumed — a typo in it is a silent return to stderr.
 */
class OnTheClasspathTest {

    @Test
    fun `nothing is bound, and a line still goes to the backend`() {
        withClue("no logger.locally anywhere above this line, which is the point of the module") {
            logger.get().shouldBeInstanceOf<Slf4jLogger>()
        }
    }

    @Test
    fun `the service file names a class that exists and takes no arguments`() {
        val found = ServiceLoader.load(Logger::class.java, Logger::class.java.classLoader).toList()

        withClue("ServiceLoader needs a no-argument constructor; every parameter having a default is") {
            found.map { it::class } shouldBe listOf(Slf4jLogger::class)
        }
    }
}
