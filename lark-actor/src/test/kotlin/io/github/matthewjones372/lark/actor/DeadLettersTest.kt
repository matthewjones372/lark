package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface Mail

private data class Letter(val n: Int) : Mail

/** Nothing the postbox takes: its step answers `unhandled()`. */
private data object Junk : Mail

/** Kept in the stash, and never put back. */
private data class Hold(val n: Int) : Mail

private data object Seal : Mail

/** Posts two letters to itself, then stops before either is handled. */
private data object Burn : Mail

private fun postbox() = behaviour<Mail, List<Int>>(emptyList()) { ctx, letters, message ->
    when (message) {
        is Letter -> become(letters + message.n)

        Junk -> unhandled()

        is Hold -> {
            ctx.stash(message)
            stay()
        }

        Seal -> stop()

        Burn -> {
            ctx.self.tell(Letter(1))
            ctx.self.tell(Letter(2))
            stop()
        }
    }
}

class DeadLettersTest {

    @Test
    fun `a message told to a stopped actor is a dead letter, once`() {
        testActors {
            val postbox = spawn("postbox", postbox())
            postbox.send(Seal)

            postbox.tell(Letter(1))

            deadLetters shouldContainExactly listOf(DeadLetter(postbox.address, Letter(1), DeadLetter.Why.Stopped))
        }
    }

    @Test
    fun `a message the step answers unhandled is a dead letter, and still in unhandled`() {
        val postbox = postbox().test()

        postbox.send(Junk)

        postbox.deadLetters shouldContainExactly listOf(DeadLetter(postbox.address, Junk, DeadLetter.Why.Unhandled))
        postbox.unhandled shouldContainExactly listOf(Junk)
    }

    @Test
    fun `what is still waiting when an actor stops is a dead letter, in the order it was told`() {
        val postbox = postbox().test()

        postbox.send(Burn)

        postbox.deadLetters shouldContainExactly listOf(
            DeadLetter(postbox.address, Letter(1), DeadLetter.Why.Stopped),
            DeadLetter(postbox.address, Letter(2), DeadLetter.Why.Stopped),
        )
    }

    @Test
    fun `a stashed message is a dead letter when the actor stops`() {
        val postbox = postbox().test()
        postbox.send(Hold(7))

        postbox.send(Seal)

        postbox.deadLetters shouldContainExactly listOf(DeadLetter(postbox.address, Hold(7), DeadLetter.Why.Stopped))
    }

    @Test
    fun `on threads, a flock hands every dead letter to its handler, once`() {
        val letters = ConcurrentLinkedQueue<DeadLetter>()
        val address = flock<Nothing, Address> {
            onDeadLetter(letters::add)
            val postbox = spawn("postbox", postbox())
            postbox.tell(Hold(7))
            postbox.tell(Junk)
            postbox.tell(Burn)
            watch(postbox).await()
            postbox.tell(Letter(3))
            postbox.address
        }.getOrNull().shouldNotBeNull()

        letters.toList() shouldContainExactlyInAnyOrder listOf(
            DeadLetter(address, Junk, DeadLetter.Why.Unhandled),
            DeadLetter(address, Hold(7), DeadLetter.Why.Stopped),
            DeadLetter(address, Letter(1), DeadLetter.Why.Stopped),
            DeadLetter(address, Letter(2), DeadLetter.Why.Stopped),
            DeadLetter(address, Letter(3), DeadLetter.Why.Stopped),
        )
        letters.size shouldBe 5
    }

    @Test
    fun `after its flock has closed, a dead letter is only logged, and a spawn is refused`() {
        val letters = ConcurrentLinkedQueue<DeadLetter>()
        val closed = flock<Nothing, Flock<Nothing>> {
            onDeadLetter(letters::add)
            spawn("postbox", postbox())
            this
        }.getOrNull()!!

        closed.deadLetter(DeadLetter(Address("local", "/user/postbox", 1), Letter(1), DeadLetter.Why.Unreachable))

        letters.toList() shouldBe emptyList()
        shouldThrow<IllegalStateException> { closed.spawn("late", postbox()) }
    }

    @Test
    fun `a dead letter the flock is told of while its actors stop reaches its handler`() {
        val letters = ConcurrentLinkedQueue<DeadLetter>()

        flock<Nothing, Unit> {
            onDeadLetter(letters::add)
            // As a transport does when its actor stops: what it could not deliver becomes this flock's dead letter.
            val transport = behaviour<Int, Unit>(Unit) { _, _, _ -> stay() }.onSignal { ctx, _, signal ->
                if (signal is Signal.Stopping) deadLetter(DeadLetter(ctx.self.address, 1, DeadLetter.Why.Unreachable))
                stay()
            }
            spawn("transport", transport)
        }

        letters.map { it.message } shouldContainExactly listOf(1)
    }

    @Test
    fun `a thread the flock did not fork, telling it of dead letters while it closes, stands no second guardian`() {
        val failed = ConcurrentLinkedQueue<Throwable>()
        val letter = DeadLetter(Address("local", "/user/postbox", 1), Letter(1), DeadLetter.Why.Unreachable)

        // A race, so it is run many times: each close meets a transport's thread at a different point.
        repeat(RACES) {
            val done = AtomicBoolean(false)
            val telling = CountDownLatch(1)
            lateinit var transport: Thread
            flock<Nothing, Unit> {
                spawn("postbox", postbox())
                val closing = this
                transport = Thread.ofPlatform().start {
                    telling.countDown()
                    try {
                        while (!done.get()) closing.deadLetter(letter)
                    } catch (thrown: IllegalStateException) {
                        failed += thrown
                    }
                }
                telling.await()
            }
            done.set(true)
            transport.join()
        }

        failed.toList() shouldBe emptyList()
    }

    private companion object {
        const val RACES = 200
    }
}
