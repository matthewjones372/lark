package io.github.matthewjones372.lark.pekko

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeSameInstanceAs
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.parZip
import io.github.matthewjones372.lark.resource
import org.apache.pekko.ConfigurationException
import org.apache.pekko.actor.ActorSystem
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

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

object LarkDispatcherSuite : Suite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = resource {
        install({ ActorSystem.create("lark-dispatcher-test", ConfigFactory.parseString(DISPATCHERS)) }) { system, _ ->
            system.terminate()
            Await.ready(system.whenTerminated(), FiniteDuration(10, TimeUnit.SECONDS))
        }
    }

    init {
        test(
            "a virtual-thread-executor dispatcher is accepted, and parZip's branches run on virtual threads",
        ) { system ->
            val on = system.larkDispatcher()

            val virtual = parZip(
                on = on,
                { Thread.currentThread().isVirtual },
                { Thread.currentThread().isVirtual },
            ) { first, second -> first to second }

            expect(virtual == (true to true)) {
                "Pekko's virtual-thread-executor hands each task a virtual thread, and a fork is a task"
            }
        }

        test("a PinnedDispatcher is accepted, and its one thread runs the branches one after another") { system ->
            val on = system.larkDispatcher("one-thread")

            val names = parZip(
                on = on,
                { Thread.currentThread().name },
                { Thread.currentThread().name },
            ) { first, second -> first to second }

            (names.first shouldBe names.second) and (names.first shouldContain "one-thread")
        }

        test("a fork-join-executor dispatcher is refused, and the message names the config key") { system ->
            shouldThrow<IllegalArgumentException>({ system.larkDispatcher("pooled") }) { refusal ->
                (refusal.message shouldContain "pekko.actor.pooled.executor") and
                    (refusal.message shouldContain "virtual-thread-executor") and
                    (refusal.message shouldContain "PinnedDispatcher")
            }
        }

        test("the id defaults to lark") { system ->
            system.larkDispatcher() shouldBeSameInstanceAs system.larkDispatcher("lark")
        }

        test("an id nobody configured leaves as Pekko's own error") { system ->
            shouldThrow<ConfigurationException>({ system.larkDispatcher("absent") }) { unconfigured ->
                unconfigured.message shouldContain "pekko.actor.absent"
            }
        }
    }
}
