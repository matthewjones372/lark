package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class Ledger
private class Lookup
private class Jammed
private class Endpoint(val health: HealthRegistry)

class HealthTest {

    @Test
    fun `a node can be handed the registry`() {
        val module = single<Ledger> { Ledger() }.probe("ledger", timeout = 5.seconds) { _: Ledger -> true } +
            single { health: HealthRegistry -> Endpoint(health) }

        val readiness = testApp(module) { route: Endpoint -> route.health.readiness() }

        readiness shouldBe Health.Up
    }

    @Test
    fun `a critical probe that stops answering makes readiness Down`() {
        val answering = AtomicBoolean(true)
        val module = single<Ledger> { Ledger() }
            .probe("ledger", timeout = 5.seconds) { _: Ledger -> answering.get() } +
            single { health: HealthRegistry -> Endpoint(health) }

        val readiness = testApp(module) { route: Endpoint ->
            answering.set(false)
            route.health.readiness()
        }

        readiness.shouldBeInstanceOf<Health.Down>().failing shouldBe listOf("ledger")
    }

    @Test
    fun `a probe that is not critical makes readiness Degraded rather than Down`() {
        val answering = AtomicBoolean(true)
        val module = single<Lookup> { Lookup() }
            .probe("lookup", timeout = 5.seconds, critical = false) { _: Lookup -> answering.get() } +
            single { health: HealthRegistry -> Endpoint(health) }

        val readiness = testApp(module) { route: Endpoint ->
            answering.set(false)
            route.health.readiness()
        }

        readiness.shouldBeInstanceOf<Health.Degraded>().failing shouldBe listOf("lookup")
    }

    @Test
    fun `a wedged probe answers inside its timeout rather than hanging`() {
        val forever = CountDownLatch(1)
        val wedge = AtomicBoolean(false)
        val module = single<Jammed> { Jammed() }
            .probe("jammed", timeout = 50.milliseconds) { _: Jammed ->
                if (wedge.get()) forever.await()
                true
            } +
            single { health: HealthRegistry -> Endpoint(health) }

        val readiness = testApp(module) { route: Endpoint ->
            wedge.set(true)
            route.health.readiness()
        }

        readiness.shouldBeInstanceOf<Health.Down>().failing shouldBe listOf("jammed")
    }

    @Test
    fun `liveness is Down until every node has started`() {
        val duringStart = AtomicReference<Health>()
        val module = single<Ledger> { Ledger() }.probe("ledger", timeout = 5.seconds) { _: Ledger -> true } +
            single { health: HealthRegistry -> duringStart.set(health.liveness()); Endpoint(health) }

        val afterStart = testApp(module) { route: Endpoint -> route.health.liveness() }

        withClue("a node reading liveness while the graph is still coming up is told so") {
            duringStart.get().shouldNotBeNull().shouldBeInstanceOf<Health.Down>()
        }
        afterStart shouldBe Health.Up
    }
}
