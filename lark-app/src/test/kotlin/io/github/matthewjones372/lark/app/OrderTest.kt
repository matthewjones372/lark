package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private class Mains
private class Pumping(val mains: Mains)
private class Tap(val pumping: Pumping)
private class Kitchen(val tap: Tap, val mains: Mains)

/**
 * Nothing is declared before what it needs.
 *
 * A graph is a set keyed by type, so `plus` is commutative: the order a service writes its modules in
 * is the order a reader wants them in, not an order the wiring depends on.
 */
class OrderTest {

    private val backwards: Module =
        singleOf(::Kitchen) + singleOf(::Tap) + singleOf(::Pumping) + singleOf(::Mains)

    private val forwards: Module =
        singleOf(::Mains) + singleOf(::Pumping) + singleOf(::Tap) + singleOf(::Kitchen)

    @Test
    fun `a graph written consumer-first starts`() {
        val kitchen = testApp(backwards) { kitchen: Kitchen -> kitchen }

        withClue("Kitchen was written before Mains existed anywhere in the file") {
            kitchen.tap.pumping.mains shouldBe kitchen.mains
        }
    }

    @Test
    fun `and plans the same as one written provider-first`() {
        val one = backwards.validate().getOrNull().shouldNotBeNull().layers
        val other = forwards.validate().getOrNull().shouldNotBeNull().layers

        one shouldBe other
    }

    @Test
    fun `and draws the same`() {
        backwards.render() shouldBe forwards.render()
    }
}
