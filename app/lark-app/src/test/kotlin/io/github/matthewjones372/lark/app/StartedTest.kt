package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import io.github.matthewjones372.lark.assert.shouldContainExactly
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.typeOf
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

    @Test
    fun `a started hook that throws fails the start as its node's refusal, and one that refuses keeps its reason`() {
        val thrown = single<Hub> { onStarted { error("no broker") }; Hub() }.use { _: Hub -> }.leftOrNull()

        val refused = thrown.shouldBeInstanceOf<StartupError.Refused>()
        refused.key shouldBe typeOf<Hub>()
        refused.reason shouldBe "its onStarted threw IllegalStateException: no broker"

        val declined = single<Hub> { onStarted { refuse("not today") }; Hub() }.use { _: Hub -> }.leftOrNull()

        declined shouldBe StartupError.Refused(typeOf<Hub>(), "not today")
    }

    @Test
    fun `a started hook asked for once the graph has started throws, rather than never running`() {
        var wiring: Wiring? = null
        val module = single<Hub> { wiring = this; Hub() }

        module.use { _: Hub ->
            shouldThrow<IllegalStateException> { wiring.shouldNotBeNull().onStarted { } }
        }.getOrNull().shouldNotBeNull()
    }
}
