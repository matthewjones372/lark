package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.capturingLogs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Spec 0130: a close waits for a read in flight, but not for ever. */
class OpenedTest {

    /** A source whose `next` blocks until [release], and whose `wake` reaches nothing. */
    private class Deaf {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        fun node() = Node.Blocking(
            open = { this },
            next = {
                reading.countDown()
                release.await()
                null
            },
            wake = { },
            close = { closed.set(true) },
            at = "OpenedTest",
        )
    }

    @Test
    fun `a read its wake does not end is closed under once the wait runs out, with a warning`() {
        val deaf = Deaf()
        val opened = Opened(deaf.node(), readEndsWithin = 200.milliseconds)
        val reader = Thread.ofVirtual().start { opened.next() }
        deaf.reading.await(1, TimeUnit.MINUTES)
        try {
            capturingLogs { logs ->
                opened.close()

                deaf.closed.get() shouldBe true
                val warned = logs.all().filter { it.level == LogLevel.Warn }
                warned shouldHaveSize 1
                warned.single().message shouldContain "closed while still reading"
            }
        } finally {
            deaf.release.countDown()
            reader.join()
        }
    }

    @Test
    fun `a close interrupted while it waits closes at once and keeps the interrupt`() {
        val deaf = Deaf()
        val opened = Opened(deaf.node(), readEndsWithin = 1.minutes)
        val reader = Thread.ofVirtual().start { opened.next() }
        deaf.reading.await(1, TimeUnit.MINUTES)
        try {
            val started = System.nanoTime()
            Thread.currentThread().interrupt()
            opened.close()

            Thread.interrupted() shouldBe true
            deaf.closed.get() shouldBe true
            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) shouldBeLessThan 10L
        } finally {
            deaf.release.countDown()
            reader.join()
        }
    }
}
