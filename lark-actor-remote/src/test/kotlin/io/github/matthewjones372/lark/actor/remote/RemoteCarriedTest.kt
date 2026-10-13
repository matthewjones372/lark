package io.github.matthewjones372.lark.actor.remote

import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.logInfo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** Asks for the request id on a line the handler writes. */
private data class WhoAsked(val reply: Reply<String>)

private val whoAskedCodec = object : MessageCodec<WhoAsked> {
    override fun write(message: WhoAsked, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn) = WhoAsked(input.reply(Codecs.string))
}

private fun answering() = behaviour<WhoAsked, Unit>(Unit) { _, _, message ->
    val heard = capturingLogs { logs ->
        logInfo("asked")
        logs.all().single().annotations["request_id"].orEmpty()
    }
    stay().also { message.reply(heard) }
}

/** Spec 0122 across nodes: what an asker had bound is what the handler on the other node runs with. */
class RemoteCarriedTest {

    @Test
    fun `an ask made inside logAnnotated is handled on another node with the asker's annotations`() {
        val port = ServerSocket(0).use { it.localPort }
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val other = Thread.ofPlatform().start {
            flock<Nothing, Unit> {
                val node = node("answering", port)
                node.expose(spawn("answer", answering()), whoAskedCodec)
                ready.countDown()
                done.await()
            }
        }
        ready.await()
        try {
            val heard = flock<Nothing, String> {
                val asking = node("asking", ServerSocket(0).use { it.localPort })
                val answer = asking.remote(Address("answering@127.0.0.1:$port", "/user/answer", 0), whoAskedCodec)
                logAnnotated("request_id" to "r-9") { answer.ask(1.minutes) { WhoAsked(it) }.getOrNull()!! }
            }.getOrNull()

            heard shouldBe "r-9"
        } finally {
            done.countDown()
            other.join()
        }
    }
}

/** Asks the other node to answer [to], a reply that tells an actor here rather than an ask's caller. */
private data class AnswerTo(val to: Reply<String>)

private val answerToCodec = object : MessageCodec<AnswerTo> {
    override fun write(message: AnswerTo, out: WireOut) = out.reply(message.to, Codecs.string)

    override fun read(input: WireIn) = AnswerTo(input.reply(Codecs.string))
}

private fun answeringTo() = behaviour<AnswerTo, Unit>(Unit) { _, _, message -> stay().also { message.to("answered") } }

/** A reply that tells [heard] the annotations on a line written as it is answered, as a saga's leg reply does. */
private class Telling(private val heard: CompletableFuture<String>) : Reply<String> {
    override val address = Address("here", "/telling", 0)

    override fun invoke(answer: String) {
        heard.complete(
            capturingLogs { logs ->
                logInfo(answer)
                logs.all().single().annotations["request_id"].orEmpty()
            },
        )
    }
}

class RemoteReplyCarriedTest {

    @Test
    fun `an answer to a reply crosses back inside the trace it was given in`() {
        val port = ServerSocket(0).use { it.localPort }
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val other = Thread.ofPlatform().start {
            flock<Nothing, Unit> {
                node("answering", port).expose(spawn("answer", answeringTo()), answerToCodec)
                ready.countDown()
                done.await()
            }
        }
        ready.await()
        try {
            val heard = CompletableFuture<String>()
            flock<Nothing, Unit> {
                val asking = node("asking", ServerSocket(0).use { it.localPort })
                val answer = asking.remote(Address("answering@127.0.0.1:$port", "/user/answer", 0), answerToCodec)
                logAnnotated("request_id" to "r-10") { answer.tell(AnswerTo(Telling(heard))) }
                heard.get(1, TimeUnit.MINUTES)
            }

            heard.get() shouldBe "r-10"
        } finally {
            done.countDown()
            other.join()
        }
    }
}
