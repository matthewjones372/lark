package io.github.matthewjones372.surefold.actor

import io.github.matthewjones372.surefold.LogLevel
import io.github.matthewjones372.surefold.LogLine
import io.github.matthewjones372.surefold.capturingMetrics
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.flock
import io.github.matthewjones372.surefold.junit.Expectations
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeInstanceOf
import io.github.matthewjones372.surefold.junit.shouldBeLessThan
import io.github.matthewjones372.surefold.junit.shouldBeLessThanOrEqual
import io.github.matthewjones372.surefold.junit.shouldBeRight
import io.github.matthewjones372.surefold.junit.shouldContainExactly
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.junit.withClue
import io.github.matthewjones372.surefold.logger
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.sql.SQLException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** Tops a wallet up by [pence], sent durably. */
private data class TopUp(val pence: Long, override val delivery: Delivery) : Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

/** Tops a wallet up, and cannot be sent again with a new delivery. */
private data class OneOff(override val delivery: Delivery) : Delivered

/** A top-up as a durable producer keeps it: the pence and whose it is, with nowhere to confirm to. */
private val topUpCodec = object : EventCodec<TopUp> {
    override fun encode(event: TopUp): ByteArray {
        require(event.pence >= 0) { "a top-up of ${event.pence} is refused" }
        return with(event.delivery) { "${event.pence}|$producer|$to|$sequence" }.toByteArray()
    }

    override fun decode(bytes: ByteArray): TopUp {
        val (pence, producer, to, sequence) = String(bytes).split("|")
        return TopUp(pence.toLong(), Delivery(producer, to, sequence.toLong(), Delivery.NoOne))
    }
}

private val pence = object : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(event).array()

    override fun decode(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).long
}

private fun walletOf(id: String) = PersistenceId("wallet", id)

private fun wallet(id: String) = delivered(
    persistent<TopUp, Long, Long>(
        id = walletOf(id),
        empty = 0,
        codec = pence,
        command = { _, _, topUp -> persist(topUp.pence) },
        event = { balance, paid -> balance + paid },
    ),
)

/** Never answers: a node that has gone. */
private val nowhere = object : ActorRef<TopUp> {
    override val address = Address("test", "/nowhere", 0)

    override fun tell(message: TopUp) = Unit
}

/** A journal whose producers' appends throw for the first [failures], as a database that is down does. */
private class Flaky(failures: Int, private val kept: Journal = InMemoryJournal()) : Journal {
    private val left = AtomicInteger(failures)

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>) =
        if (id.kind == "surefold-producer" && left.getAndDecrement() > 0) {
            throw SQLException("the database is down")
        } else {
            kept.append(id, expected, events)
        }

    override fun read(id: PersistenceId, from: Long) = kept.read(id, from)
}

/** A journal whose producers' appends throw until the test brings it [back]. */
private class Down : Journal {
    private val kept = InMemoryJournal()
    private val outage = CountDownLatch(1)

    fun back() = outage.countDown()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>) =
        if (id.kind == "surefold-producer" && outage.count > 0) {
            throw SQLException("the database is down")
        } else {
            kept.append(id, expected, events)
        }

    override fun read(id: PersistenceId, from: Long) = kept.read(id, from)
}

object DurableProducerSuite : Suite() {
    private val ids = (0 until 5).map { "w-$it" }

    init {
        test(
            "a producer started again under its id sends every unconfirmed command once, and numbers on after them",
        ) {
            testActors {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 0 until 50) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }

                val again = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
                val drained = again.drain(Duration.ZERO) shouldBe true
                val _ = again.send("w-0") { TopUp(1_000, it) }

                val each = ids.mapIndexed { w, id ->
                    val sent = (0 until 50).filter { it % 5 == w }.map(Int::toLong)
                    journal.events(walletOf(id), pence) shouldContainExactly if (w == 0) sent + 1_000 else sent
                }.reduce(Expectations::and)
                drained and each and (wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 11L))
            }
        }

        test("across databases the outbox sits in its producer's own database, and a restart finds it there") {
            val databases = listOf("db-a" to InMemoryJournal(), "db-b" to InMemoryJournal())
            val sharded = ShardedJournal(databases)
            testActors(journal = sharded) {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 0 until 10) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }

                val outbox = PersistenceId("surefold-producer", "till")
                val where = databases.map { (name, kept) ->
                    kept.read(outbox).isNotEmpty() shouldBe
                        (name == sharded.database(outbox))
                }.reduce(Expectations::and)

                val again = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
                val drained = again.drain(Duration.ZERO) shouldBe true
                where and drained and (ids.sumOf { journal.events(walletOf(it), pence).size } shouldBe 10)
            }
        }

        test("snapshots keep the outbox small, and a producer started from one numbers on") {
            testActors {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val producer = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
                for (n in 0 until 1_500) producer.send(ids[n % 5]) { TopUp(1, it) }

                // 3,000 events written; pruning keeps no more than the 1,000 since the snapshot before the newest.
                val pruned = journal.read(PersistenceId("surefold-producer", "till")).size shouldBeLessThanOrEqual 1_000
                val _ = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }.send("w-0") { TopUp(7, it) }

                pruned and (journal.events(walletOf("w-0"), pence).last() shouldBe 7L) and
                    (wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 301L))
            }
        }

        test("what a start recovers holds room, so a drain waits for it") {
            testActors {
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 0 until 3) crashed.send("w-$n") { TopUp(n.toLong(), it) }

                val again = durableProducer("till", topUpCodec, keep = 5, within = Duration.ZERO) { nowhere }

                val waits = again.drain(Duration.ZERO) shouldBe false
                val first = again.send("w-0") { TopUp(9, it) } shouldBe Unit.right()
                val second = again.send("w-1") { TopUp(9, it) } shouldBe Unit.right()
                waits and first and second and (again.send("w-2") { TopUp(9, it) } shouldBe Full.left())
            }
        }

        test("a producer resumed under an actor sends what an earlier run kept, and then drains") {
            testActors {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val crashed = durableProducer("till", topUpCodec, keep = 10) { nowhere }
                for (n in 0 until 10) crashed.send(ids[n % 5]) { TopUp(n.toLong(), it) }
                val resumed = AtomicReference<Producer<TopUp>>()
                val resumer = behaviour<Unit, Unit>(Unit) { _, _, _ -> stay() }.onStart { ctx ->
                    resumed.set(ctx.resumedProducer("till", topUpCodec, 2.seconds) { id -> wallets.getValue(id) }.first)
                }

                spawn("resumer", resumer)

                val drained = resumed.get().drain(Duration.ZERO) shouldBe true
                drained and (ids.sumOf { journal.events(walletOf(it), pence).size } shouldBe 10)
            }
        }

        test("a resumed producer whose journal throws restarts after a backoff, and still drains") {
            val kept = InMemoryJournal()
            testActors(journal = kept) {
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 0 until 5) crashed.send(ids[n]) { TopUp(n.toLong(), it) }
            }
            testActors(journal = Flaky(failures = 1, kept)) {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val resumed = AtomicReference<Pair<Producer<TopUp>, ActorRef<*>>>()
                val resumer = behaviour<Unit, Unit>(Unit) { _, _, _ -> stay() }.onStart { ctx ->
                    resumed.set(ctx.resumedProducer("till", topUpCodec, 2.seconds) { id -> wallets.getValue(id) })
                }

                spawn("resumer", resumer)

                val (producer, actor) = resumed.get()
                val delays = (actor as TestActor<*, *, *>).delays shouldBe listOf(100.milliseconds)
                val drained = producer.drain(Duration.ZERO) shouldBe true
                delays and drained and (ids.sumOf { journal.events(walletOf(it), pence).size } shouldBe 5)
            }
        }

        test(
            "a producer whose journal throws for a while sends what it writes once it is back, and holds no room",
        ) {
            testActors(journal = Flaky(failures = 4)) {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val producer =
                    durableProducer("till", topUpCodec, keep = 4, within = Duration.ZERO) { wallets.getValue(it) }
                for (n in 0 until 4) producer.send("w-0") { TopUp(n.toLong(), it) }

                val sent = producer.send("w-0") { TopUp(7, it) } shouldBe Unit.right()
                val written = journal.events(walletOf("w-0"), pence) shouldContainExactly listOf(7L)
                sent and written and (producer.drain(Duration.ZERO) shouldBe true)
            }
        }

        test(
            "on threads, a send while the journal is down answers Unwritten at once, and one after it is back is sent",
        ) {
            val within = 5.seconds
            val database = Down()
            capturingMetrics { captured ->
                val onThreads = flock<Nothing, Expectations> {
                    journal(database)
                    val wallet = spawn("w-0", wallet("w-0"))
                    val producer = durableProducer("till", topUpCodec, within = within, drainWithin = Duration.ZERO) {
                        wallet
                    }

                    // Past the first, each would wait out a backoff, 100 ms doubling, if it were told to the actor.
                    val unwritten = (1..6L).map { pence ->
                        val (sent, took) = measureTimedValue { producer.send("w-0") { TopUp(pence, it) } }
                        val answered = sent.leftOrNull().shouldBeInstanceOf<Unwritten> { answer ->
                            answer.cause.shouldBeInstanceOf<SQLException>()
                        }
                        answered and withClue("send $pence") { took shouldBeLessThan within / 10 }
                    }.reduce(Expectations::and)
                    database.back()
                    awaitIdle()

                    val sent = producer.send("w-0") { TopUp(7, it) } shouldBe Unit.right()
                    awaitIdle()
                    val written = database.events(walletOf("w-0"), pence) shouldContainExactly listOf(7L)
                    unwritten and sent and written and (producer.drain(Duration.ZERO) shouldBe true)
                }.shouldBeRight { it }
                onThreads and (captured.counter("surefold.delivery.unwritten") shouldBe 6.0)
            }
        }

        test("a producer says once that its journal is down, and once that it is back") {
            val lines = ConcurrentLinkedQueue<LogLine>()
            val sends = logger.locally({ line -> lines += line }) {
                testActors(journal = Flaky(failures = 1)) {
                    val producer = durableProducer("till", topUpCodec) { nowhere }
                    val down = producer.send("w-0") { TopUp(1, it) }.leftOrNull().shouldBeInstanceOf<Unwritten>()
                    down and (producer.send("w-0") { TopUp(2, it) } shouldBe Unit.right())
                }
            }
            val said = lines.map { it.level to it.message } shouldContainExactly listOf(
                LogLevel.Warn to "producer till cannot write to its journal, and answers Unwritten until it can: " +
                    "java.sql.SQLException: the database is down",
                LogLevel.Info to "producer till writes to its journal again",
            )
            sends and said
        }

        test("a command the codec refuses, or that cannot be sent again, throws to its sender and holds no room") {
            testActors {
                val wallets = ids.associateWith { spawn(it, wallet(it)) }
                val producer =
                    durableProducer("till", topUpCodec, keep = 1, within = Duration.ZERO) { wallets.getValue(it) }
                val oneOffs = object : EventCodec<OneOff> {
                    override fun encode(event: OneOff) = event.delivery.to.toByteArray()

                    override fun decode(bytes: ByteArray) = OneOff(Delivery("till", String(bytes), 0, Delivery.NoOne))
                }

                val refused = shouldThrow<IllegalArgumentException> { producer.send("w-0") { TopUp(-1, it) } }
                val once = shouldThrow<UnsupportedOperationException> {
                    durableProducer("once", oneOffs) { void<OneOff>() }.send("w-0", ::OneOff)
                }
                val sent = producer.send("w-0") { TopUp(3, it) } shouldBe Unit.right()

                refused and once and sent and
                    (journal.events(walletOf("w-0"), pence) shouldContainExactly listOf(3L)) and
                    (journal.read(PersistenceId("surefold-producer", "once")) shouldBe emptyList()) and
                    (producer.drain(Duration.ZERO) shouldBe true)
            }
        }

        test("a start that recovers more than it may keep drains only once every one is confirmed") {
            testActors {
                val crashed = durableProducer("till", topUpCodec) { nowhere }
                for (n in 1..5) crashed.send("w-0") { TopUp(n.toLong(), it) }
                val arrived = ArrayDeque<TopUp>()
                val wallet =
                    spawn("wallet", behaviour<TopUp, Unit>(Unit) { _, _, topUp -> stay().also { arrived += topUp } })

                val again = durableProducer("till", topUpCodec, keep = 2) { wallet }
                repeat(2) { arrived.removeFirst().delivery.confirm() }
                val waits = again.drain(Duration.ZERO) shouldBe false

                repeat(3) { arrived.removeFirst().delivery.confirm() }
                waits and (again.drain(Duration.ZERO) shouldBe true)
            }
        }

        // Spec 0146: the journal and snapshot formats are unchanged, so an outbox written before it replays.
        for ((how, snapshots) in listOf("its events" to InMemorySnapshots(), "a snapshot" to oldSnapshot())) {
            test("an outbox written before spec 0146 replays from $how, and numbers on from where it stopped") {
                val journal = InMemoryJournal()
                val _ = journal.append(OLD_OUTBOX, 0, oldEvents)
                testActors(journal = journal, snapshots = snapshots) {
                    val wallets = ids.associateWith { spawn(it, wallet(it)) }
                    val again = durableProducer("till", topUpCodec) { id -> wallets.getValue(id) }
                    val drained = again.drain(Duration.ZERO) shouldBe true
                    val _ = again.send("w-0") { TopUp(100, it) }

                    drained and
                        (journal.events(walletOf("w-0"), pence) shouldContainExactly listOf(20L, 100L)) and
                        (journal.events(walletOf("w-1"), pence) shouldContainExactly listOf(7L)) and
                        (wallets.getValue("w-0").state.delivered shouldBe mapOf("till" to 3L))
                }
            }
        }
    }
}

/** Tells nobody: an entity whose node has gone. */
private fun <M : Any> void(): ActorRef<M> = object : ActorRef<M> {
    override val address = Address("test", "/void", 0)

    override fun tell(message: M) = Unit
}

private val OLD_OUTBOX = PersistenceId("surefold-producer", "till")

/** A top-up as the till kept it: blank delivery, as `topUpCodec` writes one. */
private fun kept(pence: Long, to: String) = "$pence|till|$to|0".toByteArray()

private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()

private fun keptEvent(to: String, sequence: Long, pence: Long) = bytes {
    writeInt(1)
    writeUTF(to)
    writeLong(sequence)
    kept(pence, to).let {
        writeInt(it.size)
        write(it)
    }
}

private fun doneEvent(to: String, sequence: Long) = bytes {
    writeInt(2)
    writeUTF(to)
    writeLong(sequence)
}

/**
 * The till's journal, written byte for byte as a durable producer wrote it before spec 0146. w-0 kept 10 and 20, and
 * confirmed 10; w-1 kept 5 and 7, and confirmed 5. Each still owes its second, and numbers its next 3.
 */
private val oldEvents = listOf(
    keptEvent("w-0", 1, 10),
    keptEvent("w-1", 1, 5),
    doneEvent("w-1", 1),
    keptEvent("w-0", 2, 20),
    keptEvent("w-1", 2, 7),
    doneEvent("w-0", 1),
)

/** A snapshot of the first three of [oldEvents], as one was written before spec 0146. */
private fun oldSnapshot() = InMemorySnapshots().apply {
    save(
        OLD_OUTBOX,
        3,
        bytes {
            writeInt(2)
            writeUTF("w-0")
            writeLong(2)
            writeInt(1)
            writeLong(1)
            kept(10, "w-0").let {
                writeInt(it.size)
                write(it)
            }
            writeUTF("w-1")
            writeLong(2)
            writeInt(0)
        },
    )
}
