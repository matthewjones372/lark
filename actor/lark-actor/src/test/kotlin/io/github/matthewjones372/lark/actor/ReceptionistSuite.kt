package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldContainExactly
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.resource
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

private sealed interface Wicket

private data object Retire : Wicket

private data object Wobble : Wicket

private val Wickets = ServiceKey<Wicket>("wickets")

/** Registers itself from its start, so it is listed from before its first message and again after a restart. */
private fun wicket(log: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()) =
    behaviour<Wicket, Unit>(Unit) { _, _, message ->
        log += "step"
        when (message) {
            Retire -> stop()
            Wobble -> error("the wicket wobbled")
        }
    }.onStart { ctx ->
        log += "start"
        ctx.register(Wickets)
    }

private sealed interface Usher

private data object Follow : Usher

private data class Listing(val wickets: Set<ActorRef<Wicket>>) : Usher

private data class Sizes(val reply: Reply<List<Int>>) : Usher

/** Follows the wickets, and writes down how many there were each time it was told. */
private fun usher() = behaviour<Usher, List<Int>>(emptyList()) { ctx, sizes, message ->
    when (message) {
        Follow -> {
            ctx.subscribe(Wickets) { Listing(it) }
            stay()
        }

        is Listing -> become(sizes + message.wickets.size)

        is Sizes -> {
            message.reply(sizes)
            stay()
        }
    }
}

object ReceptionistSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("an actor registered under a key is found by it") { _ ->
            testActors {
                val wicket = spawn("wicket", wicket())

                find(Wickets) shouldBe setOf(wicket)
            }
        }

        test("onStart runs before the first message") { _ ->
            val log = ConcurrentLinkedQueue<String>()
            val wicket = wicket(log).test()

            wicket.send(Retire)

            log.toList() shouldContainExactly listOf("start", "step")
        }

        test("a subscriber is told the listing now, and again whenever it changes") { _ ->
            testActors {
                val usher = spawn("usher", usher())
                usher.send(Follow)

                val wicket = spawn("wicket", wicket())
                wicket.send(Retire)

                (usher.state shouldContainExactly listOf(0, 1, 0)) and (find(Wickets) shouldBe emptySet())
            }
        }

        test("a restart loses the registration, and onStart makes it again") { _ ->
            val log = ConcurrentLinkedQueue<String>()
            val restarted = testActors {
                val usher = spawn("usher", usher())
                usher.send(Follow)
                val wicket = spawn("wicket", wicket(log), restart = Schedule.recurs(1))

                wicket.send(Wobble)

                (wicket.restarts shouldBe 1) and
                    (usher.state shouldContainExactly listOf(0, 1, 0, 1)) and
                    (find(Wickets) shouldBe setOf(wicket))
            }
            restarted and (log.toList() shouldContainExactly listOf("start", "step", "start"))
        }

        test("a failing start fails the actor like a step") { _ ->
            val broken = behaviour<Wicket, Unit>(Unit) { _, _, _ -> stay() }.onStart { error("no start") }

            shouldThrow<IllegalStateException> { broken.test() }
        }

        test("on threads, a subscriber hears a registration and a stop, and find sees the same") { _ ->
            val heard = flock<Nothing, Any> {
                val usher = spawn("usher", usher())
                usher.tell(Follow)
                awaitIdle()
                val wicket = spawn("wicket", wicket())
                awaitIdle()
                val found = find(Wickets)
                wicket.tell(Retire)
                watch(wicket).await()
                awaitIdle()
                Triple(found == setOf(wicket), find(Wickets), usher.ask(1.minutes) { Sizes(it) })
            }

            heard shouldBeRight Triple(true, emptySet<ActorRef<Wicket>>(), listOf(0, 1, 0).right())
        }
    }
}
