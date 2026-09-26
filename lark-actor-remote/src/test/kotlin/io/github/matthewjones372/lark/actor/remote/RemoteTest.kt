package io.github.matthewjones372.lark.actor.remote

import arrow.core.right
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.awaitIdle
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

private sealed interface Ward

private data class Admit(val grams: Int) : Ward

private data class Chart(val reply: Reply<Int>) : Ward

/** Asks the ward to tell [to] each admission from now on. */
private data class Follow(val to: ActorRef<String>) : Ward

private val wardCodec = object : MessageCodec<Ward> {
    override fun write(message: Ward, out: WireOut) = when (message) {
        is Admit -> {
            out.int(1)
            out.int(message.grams)
        }

        is Chart -> {
            out.int(2)
            out.reply(message.reply, Codecs.int)
        }

        is Follow -> {
            out.int(3)
            out.ref(message.to, Codecs.string)
        }
    }

    override fun read(input: WireIn): Ward = when (val tag = input.int()) {
        1 -> Admit(input.int())
        2 -> Chart(input.reply(Codecs.int))
        3 -> Follow(input.ref(Codecs.string))
        else -> error("no ward message has the tag $tag")
    }
}

private typealias Admitted = Pair<Int, List<ActorRef<String>>>

/** The grams admitted so far, and who follows along. */
private fun ward() = behaviour<Ward, Admitted>(0 to emptyList()) { _, (grams, following), message ->
    when (message) {
        is Admit -> {
            following.forEach { it.tell("admitted ${message.grams}") }
            become(grams + message.grams to following)
        }

        is Chart -> {
            message.reply(grams)
            stay()
        }

        is Follow -> become(grams to following + message.to)
    }
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * A second node, a flock of its own on a thread of its own, with a ward exposed at `/user/ward`, until [block] is done
 * with it.
 */
private fun <A> withWardNode(block: (port: Int, ward: Address) -> A): A {
    val port = freePort()
    val ready = CountDownLatch(1)
    val done = CountDownLatch(1)
    val address = AtomicReference<Address>()
    val other = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            val node = node("hospital", port)
            val ward = spawn("ward", ward())
            node.expose(ward, wardCodec)
            address.set(Address(node.self.toString(), ward.address.path, ward.address.incarnation))
            ready.countDown()
            done.await()
        }
    }
    ready.await()
    return try {
        block(port, address.get())
    } finally {
        done.countDown()
        other.join()
    }
}

private fun Flock<Nothing>.clinic() = node("clinic", freePort())

class RemoteTest {

    @Test
    fun `a message told to an actor on another node reaches it, and an ask comes back with its answer`() {
        val charted = withWardNode { port, _ ->
            flock<Nothing, Any> {
                val ward = clinic().remote(Address("hospital@127.0.0.1:$port", "/user/ward", 0), wardCodec)
                ward.tell(Admit(50))
                ward.tell(Admit(25))
                ward.ask(1.minutes) { Chart(it) }
            }
        }

        charted shouldBe 75.right().right()
    }

    @Test
    fun `a ref inside a message crosses, and what is told to it comes back to the actor it names`() {
        val heard = ConcurrentLinkedQueue<String>()
        withWardNode { _, address ->
            flock<Nothing, Unit> {
                val ward = clinic().remote(address, wardCodec)
                val listener = spawn(
                    "listener",
                    behaviour<String, Unit>(Unit) { _, _, note -> stay().also { heard += note } },
                )
                ward.tell(Follow(listener))
                ward.tell(Admit(5))
                ward.tell(Admit(7))
                // The ward tells the listener before it answers, on the one connection back: both have arrived.
                ward.ask(1.minutes) { Chart(it) }
                awaitIdle()
            }
        }

        heard.toList() shouldContainExactly listOf("admitted 5", "admitted 7")
    }

    @Test
    fun `a message for an earlier incarnation at a path is not delivered to the actor there now`() {
        val charted = withWardNode { _, address ->
            flock<Nothing, Any> {
                val node = clinic()
                node.remote(address.copy(incarnation = address.incarnation + 1), wardCodec).tell(Admit(50))
                node.remote(address, wardCodec).ask(1.minutes) { Chart(it) }
            }
        }

        charted shouldBe 0.right().right()
    }
}
