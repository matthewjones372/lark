package io.github.matthewjones372.lark.app

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import io.github.matthewjones372.lark.assert.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class ChoiceTest {

    private enum class Kind { InProcess, Remote }

    private interface Bus

    private class Hub(val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "hub"
        }
    }

    private class LocalBus(val hub: Hub) : Bus

    private class Producer(val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "producer"
        }
    }

    private class RemoteBus(val producer: Producer) : Bus

    private class Uses(val bus: Bus, val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "uses"
        }
    }

    private val closed = CopyOnWriteArrayList<String>()

    private fun bus(kind: Kind): Module =
        single<Kind> { kind } +
            choice<Kind, Bus> { chosen ->
                when (chosen) {
                    Kind.InProcess ->
                        closingOf { -> Hub(closed) } + single { hub: Hub -> LocalBus(hub) }.boundTo<Bus>()

                    Kind.Remote ->
                        closingOf { -> Producer(closed) } +
                            single { producer: Producer -> RemoteBus(producer) }.boundTo<Bus>()
                }
            } +
            closingOf { bus: Bus -> Uses(bus, closed) }

    @Test
    fun `the setting picks the branch, and only that branch is built`() {
        testApp(bus(Kind.InProcess)) { uses: Uses -> uses.bus.shouldBeInstanceOf<LocalBus>() }
        closed shouldBe listOf("uses", "hub")
    }

    @Test
    fun `overriding the setting picks the other branch`() {
        val app = bus(Kind.InProcess).overriding(single<Kind> { Kind.Remote })

        testApp(app) { uses: Uses -> uses.bus.shouldBeInstanceOf<RemoteBus>() }
        closed shouldBe listOf("uses", "producer")
    }

    @Test
    fun `before anything runs, the choice is a node providing its key`() {
        bus(Kind.InProcess).validate().isRight() shouldBe true
        bus(Kind.InProcess).render() shouldContain "ChoiceTest_Kind --> ChoiceTest_Bus"
    }

    @Test
    fun `a branch that does not provide the key refuses the start, naming it`() {
        val app = single<Kind> { Kind.InProcess } + choice<Kind, Bus> { _ -> single<String> { "no bus" } } +
            single { bus: Bus -> Uses(bus, closed) }

        val refused = app.use { _: Uses -> }.leftOrNull()

        refused.shouldBeInstanceOf<StartupError.Refused>().reason shouldContain "does not provide it"
    }

    @Test
    fun `a branch that needs what nothing builds refuses the start`() {
        val app = single<Kind> { Kind.InProcess } +
            choice<Kind, Bus> { _ -> single { hub: Hub -> LocalBus(hub) }.boundTo<Bus>() } +
            single { bus: Bus -> Uses(bus, closed) }

        val refused = app.use { _: Uses -> }.leftOrNull()

        refused.shouldBeInstanceOf<StartupError.Refused>().reason shouldContain "needs ChoiceTest.Hub"
    }

    @Test
    fun `a key the graph already holds is not replaced by the branch`() {
        val app = bus(Kind.InProcess).overriding(single<Bus> { object : Bus {} })

        testApp(app) { uses: Uses -> (uses.bus is LocalBus) shouldBe false }
        closed shouldBe listOf("uses")
    }

    @Test
    fun `a node only the chosen branch brings can be overridden`() {
        val fake = Producer(CopyOnWriteArrayList())
        val app = bus(Kind.Remote).overriding(single<Producer> { fake })

        testApp(app) { uses: Uses -> (uses.bus as RemoteBus).producer shouldBe fake }
    }

    @Test
    fun `an override no chosen branch brings refuses the start`() {
        val app = bus(Kind.InProcess).overriding(single<Producer> { Producer(closed) })

        val refused = app.use { _: Uses -> }.leftOrNull()

        refused.shouldBeInstanceOf<StartupError.Refused>().reason shouldContain "no chosen branch provides it"
    }

    @Test
    fun `a probe in the branch is asked like any other`() {
        val app = single<Kind> { Kind.InProcess } +
            choice<Kind, Bus> { _ ->
                (closingOf { -> Hub(closed) } + single { hub: Hub -> LocalBus(hub) }.boundTo<Bus>())
                    .probe("bus", timeout = 1.seconds) { _: Bus -> false }
            } +
            single { bus: Bus -> Uses(bus, closed) }

        val refused = app.use { _: Uses -> }.leftOrNull()

        refused.shouldBeInstanceOf<StartupError.Unready>().name shouldBe "bus"
    }
}
