package io.github.matthewjones372.lark.pekko

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.parZip
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.apache.pekko.ConfigurationException
import org.apache.pekko.actor.ActorSystem
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

private val DISPATCHERS = """
    pekko.actor.lark {
        executor = "virtual-thread-executor"
    }
    pekko.actor.one-thread {
        type = PinnedDispatcher
        executor = "thread-pool-executor"
    }
    pekko.actor.pooled {
        executor = "fork-join-executor"
    }
""".trimIndent()

class LarkDispatcherTest {

    companion object {
        private val system: ActorSystem =
            ActorSystem.create("lark-dispatcher-test", ConfigFactory.parseString(DISPATCHERS))

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
        }
    }

    @Test
    fun `a virtual-thread-executor dispatcher is accepted, and parZip's branches run on virtual threads`() {
        val on = system.larkDispatcher()

        val virtual = parZip(
            on = on,
            { Thread.currentThread().isVirtual },
            { Thread.currentThread().isVirtual },
        ) { first, second -> first to second }

        withClue("Pekko's virtual-thread-executor hands each task a virtual thread, and a fork is a task") {
            virtual shouldBe (true to true)
        }
    }

    @Test
    fun `a PinnedDispatcher is accepted, and its one thread runs the branches one after another`() {
        val on = system.larkDispatcher("one-thread")

        val names = parZip(
            on = on,
            { Thread.currentThread().name },
            { Thread.currentThread().name },
        ) { first, second -> first to second }

        names.first shouldBe names.second
        names.first shouldContain "one-thread"
    }

    @Test
    fun `a fork-join-executor dispatcher is refused, and the message names the config key`() {
        val refused = shouldThrow<IllegalArgumentException> { system.larkDispatcher("pooled") }

        refused.message.orEmpty() shouldContain "pekko.actor.pooled.executor"
        refused.message.orEmpty() shouldContain "virtual-thread-executor"
        refused.message.orEmpty() shouldContain "PinnedDispatcher"
    }

    @Test
    fun `the id defaults to lark`() {
        system.larkDispatcher() shouldBeSameInstanceAs system.larkDispatcher("lark")
    }

    @Test
    fun `an id nobody configured leaves as Pekko's own error`() {
        val missing = shouldThrow<ConfigurationException> { system.larkDispatcher("absent") }

        missing.message.orEmpty() shouldContain "pekko.actor.absent"
    }
}
