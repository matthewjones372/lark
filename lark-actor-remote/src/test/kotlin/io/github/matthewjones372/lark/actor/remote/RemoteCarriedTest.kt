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
import java.util.concurrent.CountDownLatch
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
