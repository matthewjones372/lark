@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.matthewjones372.lark.actor.remote.kotlinx

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Address
import io.github.matthewjones372.lark.actor.InMemoryJournal
import io.github.matthewjones372.lark.actor.InMemorySnapshots
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.every
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.remote.Codecs
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Refs
import io.github.matthewjones372.lark.actor.remote.WireException
import io.github.matthewjones372.lark.actor.remote.decode
import io.github.matthewjones372.lark.actor.remote.encode
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.testActors
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.resource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

/** A request for a price, and the lark reply its answer goes to: what `asked` writes. */
private data class Quote(val request: Till.Paid, val reply: Reply<Long>)

private val quote = Kotlinx.asked(serializer<Till.Paid>(), Codecs.long, ::Quote, Quote::request, Quote::reply)

/** Refs that keep every reply they are given, and hand the same one back for its address. */
private class Kept : Refs {
    private val replies = mutableMapOf<Address, Reply<*>>()

    override fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>) = ref.address

    override fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>): Address =
        Address("here", "/temp/reply-${replies.size}", 0).also { replies[it] = reply }

    override fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M> = error("no refs here")

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any> reply(address: Address, answers: MessageCodec<A>) = replies.getValue(address) as Reply<A>
}

private fun <M : Any> MessageCodec<M>.roundTrip(message: M): M =
    Kept().let { refs -> decode(encode(message, refs), refs) }

private val everyKind = listOf(
    Till.Paid(Pence(250), "order-42"),
    Till.Refunded(Pence(100), Reason.Unwanted),
    Till.Closed,
)

object KotlinxSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("one data class crosses as its own bytes and reads back equal") { _ ->
            val takings = Takings(Pence(350), listOf("a", "b"), open = true)
            (Kotlinx.codec(serializer<Takings>()).roundTrip(takings) shouldBe takings) and
                (Kotlinx.state(serializer<Takings>()).let { it.decode(it.encode(takings)) } shouldBe takings)
        }

        test(
            "each class of a table reads back as the class it was written as, between nodes and in the journal",
        ) { _ ->
            (everyKind.map { tills.messages().roundTrip(it) } shouldBe everyKind) and
                (everyKind.map { tills.events().let { codec -> codec.decode(codec.encode(it)) } } shouldBe everyKind)
        }

        test("a table writes in any binary format it is given") { _ ->
            val cbor = Kotlinx.oneOf<Till>(Cbor) {
                message<Till.Paid>(1)
                message<Till.Refunded>(2)
            }
            val paid = Till.Paid(Pence(1), "cbor")
            cbor.events().let { it.decode(it.encode(paid)) } shouldBe paid
        }

        test("a table with a tag or a class given twice fails when it is built") { _ ->
            val tagTwice = shouldThrow<IllegalArgumentException>({
                Kotlinx.oneOf<Till> {
                    message<Till.Paid>(1)
                    message<Till.Refunded>(1)
                }
            }) { e -> e.message shouldContain "tag 1" }
            val classTwice = shouldThrow<IllegalArgumentException>({
                Kotlinx.oneOf<Till> {
                    message<Till.Paid>(1)
                    message<Till.Paid>(2)
                }
            }) { e -> e.message shouldContain "Paid" }
            tagTwice and classTwice
        }

        test("a class the table has no tag for is refused, and a tag it does not know is not read") { _ ->
            val short = Kotlinx.oneOf<Till> { message<Till.Paid>(1) }
            val refused =
                shouldThrow<IllegalArgumentException>({ short.write(Till.Closed) }) { e ->
                    e.message shouldContain
                        "Closed"
                }

            val closed = tills.write(Till.Closed)
            refused and
                shouldThrow<SerializationException>({ short.read(closed) }) { e -> e.message shouldContain "tag 3" } and
                shouldThrow<WireException> {
                    short.messages().decode(tills.messages().encode(Till.Closed, Kept()), Kept())
                }
        }

        test("an entity's events and snapshot are written through a table and survive a replay") { _ ->
            val journal = InMemoryJournal()
            val snapshots = InMemorySnapshots()
            val id = PersistenceId("till", "t-1")
            val till = persistent<Till, Till, Takings>(
                id = id,
                empty = Takings(Pence(0), emptyList(), open = true),
                codec = tills.events(),
                snapshots = every(2, Kotlinx.state(serializer<Takings>())),
                command = { _, _, happened -> persist(happened) },
                event = { takings, happened ->
                    when (happened) {
                        is Till.Paid -> takings.copy(
                            total = Pence(takings.total.count + happened.amount.count),
                            references = takings.references + happened.reference,
                        )

                        is Till.Refunded -> takings.copy(total = Pence(takings.total.count - happened.amount.count))

                        Till.Closed -> takings.copy(open = false)
                    }
                },
            )
            val run = {
                testActors(journal = journal, snapshots = snapshots) {
                    val ref = spawn("till", till)
                    everyKind.forEach(ref::send)
                    ref.state.value
                }
            }
            val first = run()
            val firstLife = first shouldBe Takings(Pence(150), listOf("order-42"), open = false)
            val written = journal.read(id).size shouldBe 3
            // A second life replays what the first wrote, from its snapshot on, before taking three more.
            firstLife and written and (run() shouldBe Takings(Pence(300), listOf("order-42", "order-42"), open = false))
        }

        test("an ask whose request is a data class is answered from another node") { _ ->
            val port = ServerSocket(0).use(ServerSocket::getLocalPort)
            val ready = CountDownLatch(1)
            val done = CountDownLatch(1)
            val tillNode = Thread.ofPlatform().start {
                flock<Nothing, Unit> {
                    // Answers with twice what was paid.
                    val doubling = behaviour<Quote, Unit>(Unit) { _, _, asked ->
                        stay().also { asked.reply(asked.request.amount.count * 2) }
                    }
                    node("till", port).expose(spawn("till", doubling), quote)
                    ready.countDown()
                    done.await()
                }
            }
            ready.await()
            try {
                val answer = flock<Nothing, Any> {
                    val till = node("shop", ServerSocket(0).use(ServerSocket::getLocalPort))
                        .remote(Address("till@127.0.0.1:$port", "/user/till", 0), quote)
                    till.ask(1.minutes) { Quote(Till.Paid(Pence(21), "q"), it) }
                }
                answer shouldBeRight 42L.right()
            } finally {
                done.countDown()
                tillNode.join()
            }
        }
    }
}
