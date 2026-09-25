package io.github.matthewjones372.lark.actor

import arrow.core.Option
import arrow.core.left
import arrow.core.none
import arrow.core.right
import arrow.core.some
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

private data class Pet(val id: String, val adopted: Boolean = false)

private sealed interface Shop

private data class Arrived(val pet: Pet) : Shop

private data class Find(val id: String, val reply: Reply<Option<Pet>>) : Shop

private data class Adopt(val id: String, val reply: Reply<Boolean>) : Shop

private data class Restock(val pets: List<Pet>) : Shop

private data class Forget(val id: String, val reply: Reply<Unit>) : Shop

private data object Close : Shop

private fun shop(): Behaviour<Shop, Map<String, Pet>> = behaviour(emptyMap()) { ctx, shelf, message ->
    when (message) {
        is Arrived -> become(shelf + (message.pet.id to message.pet))

        is Find -> {
            message.reply(Option.fromNullable(shelf[message.id]))
            stay()
        }

        is Adopt -> when (val pet = shelf[message.id]) {
            null -> unhandled()

            else -> {
                message.reply(!pet.adopted)
                become(shelf + (pet.id to pet.copy(adopted = true)))
            }
        }

        is Restock -> {
            message.pets.forEach { ctx.self.tell(Arrived(it)) }
            stay()
        }

        is Forget -> stay()

        Close -> stop()
    }
}

class TestActorTest {

    private val rex = Pet("rex")

    @Test
    fun `a step runs on the calling thread`() {
        val seen = AtomicReference<Thread>()
        val actor = behaviour<Shop, Unit>(Unit) { _, _, _ ->
            seen.set(Thread.currentThread())
            stay()
        }.test()

        actor.send(Close)

        seen.get() shouldBe Thread.currentThread()
    }

    @Test
    fun `send returns with the state already moved`() {
        val actor = shop().test()

        actor.send(Arrived(rex))

        actor.state shouldBe mapOf("rex" to rex)
    }

    @Test
    fun `an ask answers with the reply`() {
        val actor = shop().test()
        actor.send(Arrived(rex))

        actor.ask { Find("rex", it) } shouldBe rex.some().right()
        actor.ask { Find("tom", it) } shouldBe none<Pet>().right()
    }

    @Test
    fun `a message told to self is handled before send returns`() {
        val actor = shop().test()

        actor.send(Restock(listOf(rex, Pet("tom"))))

        actor.state.keys shouldBe setOf("rex", "tom")
    }

    @Test
    fun `a message the step never answers fails the ask at once`() {
        val actor = shop().test()

        val failure = shouldThrow<IllegalStateException> { actor.ask { Forget("rex", it) } }

        failure.message shouldContain "never replied"
    }

    @Test
    fun `a second reply fails where it is made, and stops the actor`() {
        val twice = behaviour<Shop, Unit>(Unit) { _, _, message ->
            if (message is Find) {
                message.reply(none())
                message.reply(none())
            }
            stay()
        }.test()

        shouldThrow<IllegalStateException> { twice.ask { Find("rex", it) } }

        twice.stopped shouldBe true
    }

    @Test
    fun `an unhandled message leaves the state and is recorded`() {
        val actor = shop().test()
        actor.send(Arrived(rex))

        val adopt = Adopt("tom", ignored())
        actor.send(adopt)

        actor.state shouldBe mapOf("rex" to rex)
        actor.unhandled shouldContainExactly listOf(adopt)
    }

    @Test
    fun `stop ends the actor, and an ask after it is Stopped`() {
        val actor = shop().test()

        actor.send(Close)

        actor.stopped shouldBe true
        actor.ask { Find("rex", it) } shouldBe AskFailure.Stopped.left()
        withClue("a send to a stopped actor is a mistake in the test, not a message") {
            shouldThrow<IllegalStateException> { actor.send(Arrived(rex)) }
        }
    }

    @Test
    fun `a throw stops the actor and reaches the test`() {
        val actor = behaviour<Shop, Unit>(Unit) { _, _, _ -> throw IllegalArgumentException("broken") }.test()

        shouldThrow<IllegalArgumentException> { actor.send(Close) }

        actor.stopped shouldBe true
    }

    @Test
    fun `a reply is addressed like a ref, and each ask has its own`() {
        val addresses = AtomicReference<List<Address>>(emptyList())
        val actor = behaviour<Shop, Unit>(Unit) { _, _, message ->
            if (message is Forget) {
                addresses.updateAndGet { it + message.reply.address }
                message.reply(Unit)
            }
            stay()
        }.test("keeper")

        actor.ask { Forget("a", it) }
        actor.ask { Forget("b", it) }

        actor.address shouldBe Address("test", "/user/keeper", 1)
        addresses.get().distinct().size shouldBe 2
        addresses.get().map { it.node }.distinct() shouldBe listOf("test")
    }

    @Test
    fun `adopting twice answers no the second time`() {
        val actor = shop().test()
        actor.send(Arrived(rex))

        actor.ask { Adopt("rex", it) } shouldBe true.right()
        actor.ask { Adopt("rex", it) } shouldBe false.right()
        actor.unhandled.shouldBeEmpty()
    }

    private fun <A : Any> ignored(): Reply<A> = object : Reply<A> {
        override val address = Address("test", "/temp/ignored", 1)

        override fun invoke(answer: A) = Unit
    }
}
