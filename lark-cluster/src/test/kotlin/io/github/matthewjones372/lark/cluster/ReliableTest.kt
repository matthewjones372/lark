package io.github.matthewjones372.lark.cluster

import arrow.core.right
import io.github.matthewjones372.lark.actor.Delivered
import io.github.matthewjones372.lark.actor.Delivery
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.delivered
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.remote.MessageCodec
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.actor.remote.WireIn
import io.github.matthewjones372.lark.actor.remote.WireOut
import io.github.matthewjones372.lark.actor.remote.delivery
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.shouldBe
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Pays [pence] into an account, sent reliably. */
private data class PayInto(val pence: Int, override val delivery: Delivery) : Delivered

private val payIntoCodec = object : MessageCodec<PayInto> {
    override fun write(message: PayInto, out: WireOut) {
        out.int(message.pence)
        out.delivery(message.delivery)
    }

    override fun read(input: WireIn): PayInto = PayInto(input.int(), input.delivery())
}

private val paidPence = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private fun accountOf(id: String) = PersistenceId("account", id)

/** An account remembered by what was paid into it, which confirms each payment and drops those it has had. */
private fun account(id: String) = delivered(
    persistent<PayInto, Int, Int>(
        id = accountOf(id),
        empty = 0,
        codec = paidPence,
        command = { _, _, pay -> persist(pay.pence) },
        event = { total, paid -> total + paid },
    ),
)

/** Probes calm enough that 200 entities writing to one database never make two live nodes miss each other. */
private val calm =
    Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1_000.milliseconds)

private fun openPort(): Int = ServerSocket(0).use { it.localPort }

private fun accountsDatabase(): DataSource = JdbcDataSource().apply {
    setURL("jdbc:h2:mem:accounts-${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    val ddl = checkNotNull(JdbcJournal::class.java.getResource("/lark/journal/jdbc/h2.sql")).readText()
    connection.use { connection -> connection.createStatement().use { statement -> statement.execute(ddl) } }
}

/** A node with the accounts sharded on it and [journal] as its flock's, on a thread of its own, until [close]. */
private class Bank(val name: String, port: Int, seeds: Discovery, journal: Journal, leaveWithin: Duration) :
    AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sharded<PayInto>>>()
    private val thread = Thread.ofPlatform().start {
        flock<Nothing, Unit> {
            journal(journal)
            val cluster =
                cluster(node(name, port), seeds, calm, Downing.keepMajority(stableAfter = 3.seconds), leaveWithin)
            val accounts = cluster.sharding("account", payIntoCodec, passivateAfter = 1.minutes, entity = ::account)
            opened.set(cluster to accounts)
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get().first
        }

    val accounts: Sharded<PayInto>
        get() {
            ready.await()
            return opened.get().second
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

class ReliableTest {

    @Test
    fun `payments to 200 accounts while a node crashes are each applied exactly once, in order`() {
        val journal = JdbcJournal(accountsDatabase())
        val ports = List(3) { openPort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        // The third goes as a crashed node does, without leaving.
        val banks = ports.mapIndexed { i, port ->
            Bank("b${i + 1}", port, seeds, journal, if (i == 2) Duration.ZERO else 30.seconds)
        }
        try {
            banks.forEach { bank ->
                bank.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == 3 } shouldBe
                    true
            }
            val (sender, stays, crashing) = banks
            val payments = sender.accounts.reliable("checkout", resendAfter = 200.milliseconds, keep = 1_000)
            val accounts = List(200) { "a-$it" }

            fun pay(pence: Int) = accounts.forEach { id ->
                payments.send(id) { PayInto(pence, it) } shouldBe
                    Unit.right()
            }

            (1..2).forEach(::pay)
            payments.drain(1.minutes) shouldBe true
            // The third node stops without leaving, with the third payments on their way to its accounts: what was
            // in flight to it is lost, and what it wrote may not have been confirmed.
            pay(3)
            crashing.close()
            (4..5).forEach(::pay)
            listOf(sender, stays).forEach { bank ->
                bank.cluster.await(1.minutes) { view -> view.members.none { it.node.name == crashing.name } } shouldBe
                    true
            }

            payments.drain(40.seconds) shouldBe true
            accounts.forEach { id -> journal.events(accountOf(id), paidPence) shouldBe (1..5).toList() }
        } finally {
            banks.forEach(Bank::close)
        }
    }

    @Test
    fun `payments a node sends just before it closes are each applied once, from wherever their accounts run`() {
        val journal = JdbcJournal(accountsDatabase())
        val ports = List(3) { openPort() }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val banks = ports.mapIndexed { i, port -> Bank("b${i + 1}", port, seeds, journal, 30.seconds) }
        try {
            banks.forEach { bank ->
                bank.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == 3 } shouldBe
                    true
            }
            val closing = banks.last()
            val payments = closing.accounts.reliable("checkout", keep = 1_000)
            val accounts = List(200) { "a-$it" }

            for (pence in 1..2) {
                accounts.forEach { id -> payments.send(id) { PayInto(pence, it) } shouldBe Unit.right() }
            }
            closing.close()

            accounts.forEach { id -> journal.events(accountOf(id), paidPence) shouldBe listOf(1, 2) }
        } finally {
            banks.forEach(Bank::close)
        }
    }
}
