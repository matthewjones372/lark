package io.github.matthewjones372.lark.app

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

private class Hub
private class Spoke

/** Spec 0129: what a node does once the whole graph is built, and a probe asked only then. */
class StartedTest {

    @Test
    fun `started hooks run once every node is built, in the graph's order, and a WhenStarted probe after them`() {
        val log = ConcurrentLinkedQueue<String>()
        val module = single<Hub> {
            log += "hub built"
            onStarted { log += "hub started" }
            Hub()
        }.probe("hub", timeout = 5.seconds, asked = Asked.WhenStarted) { _: Hub -> log += "hub asked"; true } +
            single { _: Hub ->
                log += "spoke built"
                onStarted { log += "spoke started" }
                Spoke()
            }

        module.use { _: Spoke -> log += "running" }.getOrNull().shouldNotBeNull()

        log.toList() shouldContainExactly
            listOf("hub built", "spoke built", "hub started", "spoke started", "hub asked", "running")
    }

    @Test
    fun `a WhenStarted probe that never passes fails the start, naming its node, after every node is built`() {
        val built = ConcurrentLinkedQueue<String>()
        val module = single<Hub> { Hub() }
            .probe("hub", timeout = 5.seconds, asked = Asked.WhenStarted) { _: Hub -> false } +
            single { _: Hub -> built += "spoke"; Spoke() }

        val error = module.use { _: Spoke -> }.leftOrNull().shouldNotBeNull()

        error.shouldBeInstanceOf<StartupError.Unready>().name shouldBe "hub"
        built.toList() shouldContainExactly listOf("spoke")
    }
}
