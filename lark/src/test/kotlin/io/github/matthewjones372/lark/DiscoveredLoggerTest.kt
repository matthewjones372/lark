package io.github.matthewjones372.lark

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/** Where a line goes when nobody has bound anything: whatever the classpath registered, or stderr. */
class DiscoveredLoggerTest {

    @Test
    fun `nothing registered leaves stderr, which is enough to watch a service start`() {
        withClue("this module registers no Logger of its own, so this is the fallback under test") {
            ServiceLoader.load(Logger::class.java, Logger::class.java.classLoader).count() shouldBe 0
        }
        logger.get() shouldBe StderrLogger
    }

    @Test
    fun `a binding still wins over whatever the classpath had`() {
        val mine = Logger { }

        logger.locally(mine) { logger.get() } shouldBe mine
    }
}
