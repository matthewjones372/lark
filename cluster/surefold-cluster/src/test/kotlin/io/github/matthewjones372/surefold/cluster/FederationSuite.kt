package io.github.matthewjones372.surefold.cluster

import io.github.matthewjones372.surefold.actor.AskFailure
import io.github.matthewjones372.surefold.actor.DeadLetter
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.onDeadLetter
import io.github.matthewjones372.surefold.actor.remote.Codecs
import io.github.matthewjones372.surefold.actor.remote.MessageCodec
import io.github.matthewjones372.surefold.actor.remote.Node
import io.github.matthewjones372.surefold.actor.remote.WireIn
import io.github.matthewjones372.surefold.actor.remote.WireOut
import io.github.matthewjones372.surefold.actor.remote.node
import io.github.matthewjones372.surefold.actor.stay
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeRight
import io.github.matthewjones372.surefold.junit.withClue
import io.github.matthewjones372.surefold.scope
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** Asks which cluster answers for an account. */
private data class WhoHolds(val reply: Reply<String>)

private val whoHoldsCodec = object : MessageCodec<WhoHolds> {
    override fun write(message: WhoHolds, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn): WhoHolds = WhoHolds(input.reply(Codecs.string))
}

private val brisk = Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 500.milliseconds)

private fun openPort(): Int = ServerSocket(0).use { it.localPort }

private fun answering(name: String) =
    behaviour<WhoHolds, Unit>(Unit) { _, _, asked -> stay().also { asked.reply(name) } }

/**
 * A one-node cluster named [name] at [port], hosting "account", until closed: its entities answer with [name]. It
 * reaches the accounts of the cluster at [northPort] as "north".
 */
private class OneNodeCluster(name: String, port: Int, northPort: Int) : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val done = CountDownLatch(1)
    private lateinit var north: FederatedKind<WhoHolds>
    private val thread = Thread.ofPlatform().start {
        scope {
            val member = cluster(node(name, port), Discovery.static(Node("", "127.0.0.1", port)), brisk)
            check(member.await(1.minutes) { v -> v.members.any { it.status == Status.Up } }) { "$name never came up" }
            val _ = member.sharding("account", whoHoldsCodec, 1.minutes) { answering(name) }
            north = member.federate("north", Discovery.static(Node("", "127.0.0.1", northPort)))
                .sharding("account", whoHoldsCodec)
            ready.countDown()
            done.await()
        }
    }

    /** Who answers for [id] in the north cluster, asked from this one. */
    fun askNorth(id: String) = north.entity(id).ask(1.minutes) { WhoHolds(it) }

    init {
        ready.await()
    }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

/** Spec 0103: one cluster asks another's entities, through its regions, without joining it. */
object FederationSuite : Suite() {
    init {
        test("a cluster asks another's entities, fails at once while it is gone, and asks again once it is back") {
            val southPort = openPort()
            val northPort = openPort()
            val letters = LinkedBlockingQueue<DeadLetter>()
            var south = OneNodeCluster("south", southPort, northPort)
            try {
                scope {
                    onDeadLetter(letters::add)
                    val self = Discovery.static(Node("", "127.0.0.1", northPort))
                    val north = cluster(node("north", northPort), self, brisk)
                    check(north.await(1.minutes) { v -> v.members.any { it.status == Status.Up } }) { "north is down" }
                    val _ = north.sharding("account", whoHoldsCodec, 1.minutes) { answering("north") }
                    val accounts = north.federate(
                        "south",
                        Discovery.static(Node("", "127.0.0.1", southPort)),
                        retryAfter = 1.seconds,
                    ).sharding("account", whoHoldsCodec)

                    accounts.entity("s-7").ask(1.minutes) { WhoHolds(it) } shouldBeRight "south"
                    south.askNorth("n-3") shouldBeRight "north"

                    south.close()
                    // A frame written before north sees the connection close is lost, not dropped: at most once. Told
                    // until one is dropped, so north knows south is gone before it asks.
                    do {
                        accounts.entity("s-9").tell(WhoHolds(DroppedReply))
                    } while (letters.poll(100, TimeUnit.MILLISECONDS)?.why != DeadLetter.Why.Unreachable)
                    val gone = measureTimedValue { accounts.entity("s-7").ask(1.minutes) { WhoHolds(it) } }
                    gone.value shouldBe AskFailure.Unreachable.left()
                    withClue("the ask answered after ${gone.duration}, of the minute it was given") {
                        (gone.duration < 20.seconds) shouldBe true
                    }
                    accounts.entity("s-8").tell(WhoHolds(DroppedReply))
                    val letter = generateSequence { letters.poll(1, TimeUnit.MINUTES) }
                        .first { it.why == DeadLetter.Why.Unreachable && it.recipient.path == Sharding.path("account") }
                    letter.recipient.node shouldBe "@127.0.0.1:$southPort"

                    south = OneNodeCluster("south", southPort, northPort)
                    val back = generateSequence { accounts.entity("s-7").ask(2.seconds) { WhoHolds(it) } }
                        .take(60)
                        .firstOrNull { it.isRight() }
                    withClue("an ask once south is back, without restarting north") { back shouldBe "south".right() }
                }
            } finally {
                south.close()
            }
        }
    }
}

/** A reply nobody waits on, for a tell that is meant to be a dead letter. */
private object DroppedReply : Reply<String> {
    override val address = io.github.matthewjones372.surefold.actor.Address("", "/dropped", 0)

    override fun invoke(answer: String) = Unit
}
