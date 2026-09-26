package io.github.matthewjones372.lark.actor.remote

import arrow.core.right
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.DeadLetter
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.awaitIdle
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onDeadLetter
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.stop
import io.github.matthewjones372.lark.actor.unhandled
import io.github.matthewjones372.lark.actor.watch
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
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

/** The second node, as a test sees it: where it listens, its ward, its dead letters, and a way to stop the ward. */
private class Hospital(val port: Int, val ward: Address, val letters: ConcurrentLinkedQueue<DeadLetter>) {
    val stopWard = CountDownLatch(1)
}

/**
 * A second node, a flock of its own on a thread of its own, with a ward exposed at `/user/ward`, until [block] is done
 * with it.
 */
private fun <A> withWardNode(block: (Hospital) -> A): A {
    val port = freePort()
    val ready = CountDownLatch(1)
    val done = CountDownLatch(1)
    val hospital = AtomicReference<Hospital>()
    val other = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            val letters = ConcurrentLinkedQueue<DeadLetter>()
            onDeadLetter(letters::add)
            val node = node("hospital", port)
            val ward = spawn("ward", ward())
            node.expose(ward, wardCodec)
            val address = Address(node.self.toString(), ward.address.path, ward.address.incarnation)
            hospital.set(Hospital(port, address, letters))
            ready.countDown()
            val stopping = Thread.ofVirtual().start {
                hospital.get().stopWard.await()
                stop(ward).await()
            }
            done.await()
            stopping.interrupt()
        }
    }
    ready.await()
    return try {
        block(hospital.get())
    } finally {
        done.countDown()
        other.join()
    }
}

private fun Flock<Nothing>.clinic() = node("clinic", freePort())

class RemoteTest {

    @Test
    fun `a message told to an actor on another node reaches it, and an ask comes back with its answer`() {
        val charted = withWardNode { hospital ->
            flock<Nothing, Any> {
                val ward = clinic().remote(Address("hospital@127.0.0.1:${hospital.port}", "/user/ward", 0), wardCodec)
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
        withWardNode { hospital ->
            flock<Nothing, Unit> {
                val ward = clinic().remote(hospital.ward, wardCodec)
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
    fun `a message for an earlier incarnation at a path is a dead letter there, not a message for the actor now`() {
        val (charted, letters, earlier) = withWardNode { hospital ->
            val earlier = hospital.ward.copy(incarnation = hospital.ward.incarnation + 1)
            val charted = flock<Nothing, Any> {
                val node = clinic()
                node.remote(earlier, wardCodec).tell(Admit(50))
                node.remote(hospital.ward, wardCodec).ask(1.minutes) { Chart(it) }
            }
            Triple(charted, hospital.letters.toList(), earlier)
        }

        charted shouldBe 0.right().right()
        letters shouldContainExactly listOf(DeadLetter(earlier, Admit(50), DeadLetter.Why.NoSuchActor))
    }

    @Test
    fun `a message for a path no actor is at is a dead letter on the node it reached`() {
        val letters = withWardNode { hospital ->
            flock<Nothing, Any> {
                val node = clinic()
                node.remote(hospital.ward.copy(path = "/user/nobody"), wardCodec).tell(Admit(5))
                // One connection, in order: once the ward answers, the frame before it has been dealt with.
                node.remote(hospital.ward, wardCodec).ask(1.minutes) { Chart(it) }
            }
            hospital.letters.toList()
        }

        letters.map { it.recipient.path to it.why } shouldContainExactly
            listOf("/user/nobody" to DeadLetter.Why.NoSuchActor)
        wardCodec.decode((letters.single().message as UnreadMessage).bytes, noRefs) shouldBe Admit(5)
    }

    @Test
    fun `a message told to a node nobody answers for is a dead letter here, with the message in it`() {
        val letters = LinkedBlockingQueue<DeadLetter>()
        val nowhere = Address("nowhere@127.0.0.1:${freePort()}", "/user/ward", 0)
        flock<Nothing, Unit> {
            onDeadLetter(letters::add)
            clinic().remote(nowhere, wardCodec).tell(Admit(5))
            letters.poll(1, TimeUnit.MINUTES) shouldBe DeadLetter(nowhere, Admit(5), DeadLetter.Why.Unreachable)
        }
    }

    @Test
    fun `an actor watching one on another node hears Terminated when it stops`() {
        val heard = withWardNode { hospital ->
            flock<Nothing, Any> {
                val ward = clinic().remote(hospital.ward, wardCodec)
                val lookout = spawn("lookout", lookout())
                lookout.tell(ward)
                // The watch has reached the hospital before the ward stops, since a Chart behind it came back.
                ward.ask(1.minutes) { Chart(it) }
                val watched = watch(ward)
                hospital.stopWard.countDown()
                watched.await()
                awaitIdle()
                lookout.ask(1.minutes) { SoFar(it) }
            }
        }

        heard shouldBe listOf("Terminated").right().right()
    }

    @Test
    fun `a watch on an actor whose node goes away ends once the node has been unreachable long enough`() {
        val port = freePort()
        val ready = CountDownLatch(1)
        val gone = CountDownLatch(1)
        val hospital = Thread.ofPlatform().start {
            flock<Nothing, Unit> {
                node("hospital", port).expose(spawn("ward", ward()), wardCodec)
                ready.countDown()
                gone.await()
            }
        }
        ready.await()
        val ended = flock<Nothing, Any> {
            val ward = node("clinic", freePort(), unreachableAfter = 100.milliseconds)
                .remote(Address("hospital@127.0.0.1:$port", "/user/ward", 0), wardCodec)
            ward.ask(1.minutes) { Chart(it) }
            val watched = watch(ward)
            gone.countDown()
            hospital.join()
            watched.await()
        }

        ended.map { it.javaClass.simpleName } shouldBe "Terminated".right()
    }
}

private sealed interface Lookout

private data class SoFar(val reply: Reply<List<String>>) : Lookout

/** Watches every ref it is told of, and answers with what it has heard since. */
private fun lookout() = behaviour<Any, List<String>>(emptyList()) { ctx, heard, message ->
    when (message) {
        is ActorRef<*> -> {
            ctx.watch(message)
            stay()
        }

        is SoFar -> {
            message.reply(heard)
            stay()
        }

        else -> unhandled()
    }
}.onSignal { _, heard, signal ->
    if (signal is Signal.Terminated) become(heard + "Terminated") else stay()
}

/** Refs for decoding a message that holds none. */
private val noRefs = object : Refs {
    override fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>) = error("no refs here")

    override fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>) = error("no refs here")

    override fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M> = error("no refs here")

    override fun <A : Any> reply(address: Address, answers: MessageCodec<A>): Reply<A> = error("no refs here")
}
