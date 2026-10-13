package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

private sealed interface Shift

private data class Hire(val name: String) : Shift

private data object Shut : Shift

private data object Trip : Shift

private data object Rest

/** Records its own Stopping in [log], under [name]. */
private fun clerk(name: String, log: AtomicReference<List<String>>) = behaviour<Rest, Unit>(Unit) { _, _, _ ->
    stay()
}.onSignal { _, _, signal ->
    if (signal == Signal.Stopping) log.updateAndGet { it + name }
    stay()
}

/** Hires clerks as children; `Shut` stops it, `Trip` fails it. */
private fun manager(log: AtomicReference<List<String>>) = behaviour<Shift, Unit, String>(Unit) { ctx, _, message ->
    when (message) {
        is Hire -> {
            ctx.spawn(message.name, clerk(message.name, log))
            stay()
        }

        Shut -> stop()

        Trip -> raise("tripped")
    }
}.onSignal { _, _, signal ->
    if (signal == Signal.Stopping) log.updateAndGet { it + "manager" }
    stay()
}

class ChildrenTest {

    @Test
    fun `a child's Stopping comes before its parent's`() {
        val log = AtomicReference<List<String>>(emptyList())
        testActors {
            val manager = spawn("manager", manager(log))
            manager.send(Hire("ann"))
            manager.send(Hire("bob"))

            manager.children.map { it.address.path } shouldBe listOf("/user/manager/ann", "/user/manager/bob")
            manager.send(Shut)
        }

        log.get() shouldBe listOf("ann", "bob", "manager")
    }

    @Test
    fun `a parent's restart stops its children first, and the parent carries on`() {
        val log = AtomicReference<List<String>>(emptyList())
        testActors {
            val manager = spawn("manager", manager(log), restart = Schedule.recurs(1))
            manager.send(Hire("ann"))

            manager.send(Trip)

            manager.stopped shouldBe false
            manager.children shouldBe emptyList()
        }

        log.get() shouldBe listOf("ann")
    }

    @Test
    fun `on threads, a child's Stopping comes before its parent's`() {
        val log = AtomicReference<List<String>>(emptyList())
        flock<Nothing, Unit> {
            val manager = spawn("manager", manager(log))
            manager.tell(Hire("ann"))
            manager.tell(Shut)
            watch(manager).await()
        }

        log.get() shouldBe listOf("ann", "manager")
    }

    @Test
    fun `on threads, closing the flock stops a child before its parent`() {
        val log = AtomicReference<List<String>>(emptyList())
        flock<Nothing, Unit> {
            spawn("manager", manager(log)).tell(Hire("ann"))
            awaitIdle()
        }

        log.get() shouldBe listOf("ann", "manager")
    }

    @Test
    fun `on threads, a parent's restart stops its children first`() {
        val log = AtomicReference<List<String>>(emptyList())
        flock<Nothing, Unit> {
            val manager = spawn("manager", manager(log), restart = Schedule.recurs(1))
            manager.tell(Hire("ann"))
            manager.tell(Trip)
            awaitIdle()
            log.get() shouldBe listOf("ann")
        }
    }
}
