package io.github.matthewjones372.lark.actor

import arrow.core.right
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.minutes

private sealed interface PetEvent

private data class Fed(val grams: Int) : PetEvent

private data class Homed(val by: String) : PetEvent

private val petCodec = object : EventCodec<PetEvent> {
    override fun encode(event: PetEvent): ByteArray = when (event) {
        is Fed -> "fed:${event.grams}"
        is Homed -> "homed:${event.by}"
    }.toByteArray()

    override fun decode(bytes: ByteArray): PetEvent {
        val (kind, value) = String(bytes).split(":", limit = 2)
        return if (kind == "fed") Fed(value.toInt()) else Homed(value)
    }
}

private data class Hound(val grams: Int = 0, val home: String? = null)

private sealed interface PetCommand

private data class Eat(val grams: Int) : PetCommand

private data class Weigh(val reply: Reply<Int>) : PetCommand

private data class Home(val by: String, val reply: Reply<String>) : PetCommand

private data object Wander : PetCommand

private fun houndOf(id: String) = PersistenceId("hound", id)

/** A hound remembered by its events: fed and homed, weighed and asked where it lives. */
private fun hound(id: String, log: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()) =
    persistent<PetCommand, PetEvent, Hound>(
        id = houndOf(id),
        empty = Hound(),
        codec = petCodec,
        command = { _, hound, command ->
            when (command) {
                is Eat -> persist(Fed(command.grams)).then { log += "$id weighs ${it.grams}" }

                is Weigh -> none().then { command.reply(hound.grams) }

                is Home ->
                    if (hound.home != null) {
                        none().then { command.reply(hound.home) }
                    } else {
                        persist(Homed(command.by)).then { command.reply(command.by) }
                    }

                Wander -> unhandled()
            }
        },
        event = { hound, event ->
            when (event) {
                is Fed -> hound.copy(grams = hound.grams + event.grams)
                is Homed -> hound.copy(home = event.by)
            }
        },
    )

class PersistentTest {

    private val log = ConcurrentLinkedQueue<String>()

    @Test
    fun `a command persists its events, the state is what they built, and then runs after both`() {
        testActors {
            val rex = spawn("rex", hound("rex", log))

            rex.send(Eat(50))
            rex.send(Eat(25))

            rex.state shouldBe Remembered(Hound(grams = 75), sequence = 2)
            journal.events(houndOf("rex"), petCodec) shouldContainExactly listOf(Fed(50), Fed(25))
            log.toList() shouldContainExactly listOf("rex weighs 50", "rex weighs 75")
        }
    }

    @Test
    fun `after a restart the state is what the journal holds`() {
        testActors {
            val rex = spawn("rex", hound("rex"))
            rex.send(Eat(50))
            rex.ask { Home("sam", it) }

            rex.restart()

            rex.state shouldBe Remembered(Hound(grams = 50, home = "sam"), sequence = 2)
            rex.ask { Home("bo", it) } shouldBe "sam".right()
        }
    }

    @Test
    fun `a command that persists nothing writes nothing, and unhandled is unhandled`() {
        testActors {
            val rex = spawn("rex", hound("rex"))

            rex.ask { Weigh(it) } shouldBe 0.right()
            rex.send(Wander)

            journal.events(houndOf("rex"), petCodec) shouldContainExactly emptyList()
            rex.unhandled shouldContainExactly listOf(Wander)
        }
    }

    @Test
    fun `two writers for one id cannot both succeed, and the second fails with the conflict`() {
        testActors {
            val first = spawn("first", hound("rex"))
            val second = spawn("second", hound("rex"))

            first.send(Eat(50))
            second.send(Eat(10))

            second.failure shouldBe Failure.Raised(JournalConflict(houndOf("rex"), expected = 0, actual = 1))
            journal.events(houndOf("rex"), petCodec) shouldContainExactly listOf(Fed(50))
        }
    }

    @Test
    fun `a conflict restarts the actor when its schedule says so, and it recovers what the other wrote`() {
        testActors {
            val first = spawn("first", hound("rex"))
            val second = spawn("second", hound("rex"), restart = Schedule.recurs(1))
            first.send(Eat(50))

            second.send(Eat(10))
            second.send(Eat(10))

            second.restarts shouldBe 1
            second.state shouldBe Remembered(Hound(grams = 60), sequence = 2)
        }
    }

    @Test
    fun `on threads, an entity passivated and started again comes back with the state its events built`() {
        val moving = TestClock()
        val journal = InMemoryJournal()
        val weight = clock.locally(moving) {
            flock<Nothing, Any> {
                journal(journal)
                val kennel = spawn("kennel", entities(passivateAfter = 10.minutes) { id -> hound(id, log) })
                kennel.entity("rex").tell(Eat(50))
                kennel.entity("rex").tell(Eat(25))
                awaitIdle()

                moving.adjust(10.minutes)
                awaitIdle()
                kennel.entity("rex").ask(1.minutes) { Weigh(it) }
            }
        }

        weight shouldBe 75.right().right()
        journal.events(houndOf("rex"), petCodec) shouldContainExactly listOf(Fed(50), Fed(25))
    }

    @Test
    fun `on threads, a conflict restarts the actor, and its start recovers what the other writer wrote`() {
        val journal = InMemoryJournal()
        val weight = flock<Nothing, Any> {
            journal(journal)
            val first = spawn("first", hound("rex"))
            val second = spawn("second", hound("rex"), restart = Schedule.recurs(1))
            awaitIdle()
            first.tell(Eat(50))
            awaitIdle()

            second.tell(Eat(10))
            second.tell(Eat(10))
            second.ask(1.minutes) { Weigh(it) }
        }

        weight shouldBe 60.right().right()
        journal.events(houndOf("rex"), petCodec) shouldContainExactly listOf(Fed(50), Fed(10))
    }
}
