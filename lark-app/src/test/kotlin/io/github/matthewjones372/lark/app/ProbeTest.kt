package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class Broker
private class Consumer
private class Wedged

class ProbeTest {

    @Test
    fun `a dependent waits for the probe, not just the recipe`() {
        val ready = AtomicBoolean()
        val sawReady = AtomicBoolean()
        val module = single<Broker> { Broker() }
            .probe("broker", timeout = 5.seconds) { _: Broker -> ready.set(true); true } +
            single { _: Broker -> sawReady.set(ready.get()); Consumer() }

        module.use { _: Consumer -> }.getOrNull().shouldNotBeNull()

        withClue("Consumer is built only after the broker answered") { sawReady.get() shouldBe true }
    }

    @Test
    fun `a probe that never passes fails the start, naming the node`() {
        val module = single<Broker> { Broker() }.probe("broker", timeout = 5.seconds) { _: Broker -> false }

        val error = module.use { _: Broker -> }.leftOrNull().shouldNotBeNull()

        error.shouldBeInstanceOf<StartupError.Unready>().name shouldBe "broker"
        error.shouldBeInstanceOf<StartupError.Unready>().key shouldBe typeOf<Broker>()
    }

    @Test
    fun `a probe that hangs fails inside its timeout`() {
        val forever = CountDownLatch(1)
        val module = single<Wedged> { Wedged() }
            .probe("wedged", timeout = 50.milliseconds) { _: Wedged -> forever.await(); true }

        val error = module.use { _: Wedged -> }.leftOrNull().shouldNotBeNull()

        error.shouldBeInstanceOf<StartupError.Unready>().name shouldBe "wedged"
    }

    @Test
    fun `what was acquired before an unready node is given back`() {
        val released = AtomicBoolean()
        val module = single<Broker> { install({ Broker() }) { _, _ -> released.set(true) } } +
            single<Consumer, Broker> { Consumer() }.probe("consumer", timeout = 5.seconds) { _: Consumer -> false }

        module.use { _: Consumer -> }.leftOrNull().shouldNotBeNull()

        released.get() shouldBe true
    }

    @Test
    fun `overriding a key drops the probe that was asked of it`() {
        val asked = AtomicInteger()
        val module = (
            single<Broker> { Broker() }.probe("broker", timeout = 5.seconds) { _: Broker ->
                asked.incrementAndGet()
                false
            }
            ).overriding(single<Broker> { Broker() })

        module.use { _: Broker -> }.getOrNull().shouldNotBeNull()

        withClue("the fake is not the thing the probe was written about") { asked.get() shouldBe 0 }
    }

    @Test
    fun `a subgraph keeps only the probes of the nodes it kept`() {
        val asked = AtomicBoolean()
        val module = single<Broker> { Broker() }
            .probe("broker", timeout = 5.seconds) { _: Broker -> asked.set(true); true } +
            single<Wedged> { Wedged() }

        testApp(module.subgraph<Wedged>()) { _: Wedged -> }

        asked.get() shouldBe false
    }
}
