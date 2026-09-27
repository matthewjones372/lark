package io.github.matthewjones372.lark.cluster

import arrow.core.right
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.JournalPruning
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.remote.Node
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [journal], keeping each append that another writer beat: two producers running under one id. */
private class Conflicts(private val journal: JdbcJournal) :
    Journal by journal,
    JournalPruning by journal {
    val seen = CopyOnWriteArrayList<JournalConflict>()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>) =
        journal.append(id, expected, events).onLeft { seen += it }
}

class ProducersTest {

    @Test
    fun `two lives of one node, restarted in place, keep separate outboxes, and each one's commands are delivered`() {
        val journal = JdbcJournal(accountsDatabase())
        val ports = List(3) { ServerSocket(0).use(ServerSocket::getLocalPort) }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        // Every node goes as a crashed one does, so the third's earlier life is still held when it comes back.
        val bank = { i: Int -> Bank("b${i + 1}", ports[i], seeds, journal, Duration.ZERO) }
        val banks = (0 until 3).map(bank).toMutableList()
        val ids = List(20) { "a-$it" }
        fun Bank.up() = cluster.await(1.minutes) { view ->
            view.members.count { it.status == Status.Up } == 3 && view.members.any { cluster.isSelf(it) }
        }
        fun Bank.pay(pence: Int) {
            val payments = accounts.reliable("checkout", durable = true)
            ids.forEach { id -> payments.send(id) { PayInto(pence, it) } shouldBe Unit.right() }
            payments.drain(1.minutes) shouldBe true
        }
        try {
            banks.forEach { it.up() shouldBe true }
            val earlier = banks.removeLast()
            val lives = mutableListOf(earlier.cluster.life)
            earlier.pay(1)
            earlier.close()
            val later = bank(2).also(banks::add)
            later.up() shouldBe true
            lives += later.cluster.life
            later.pay(2)

            ids.forEach { id -> journal.events(accountOf(id), paidPence) shouldBe listOf(1, 2) }
            lives.distinct().size shouldBe 2
            // Each life kept its 20 payments and their confirmations, and nothing is kept under the id alone.
            lives.forEach { life ->
                journal.read(PersistenceId("lark-producer", "account-checkout-$life")).size shouldBe 40
            }
            journal.read(PersistenceId("lark-producer", "account-checkout")).shouldBeEmpty()
        } finally {
            banks.forEach(Bank::close)
        }
    }

    @Test
    fun `on three nodes, the registry lists each node's durable producer with the life that runs it`() {
        val journal = JdbcJournal(accountsDatabase())
        val ports = List(3) { ServerSocket(0).use(ServerSocket::getLocalPort) }
        val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
        val banks = ports.mapIndexed { i, port -> Bank("b${i + 1}", port, seeds, journal, Duration.ZERO) }
        try {
            banks.forEach { bank ->
                bank.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == 3 } shouldBe
                    true
            }
            banks.forEach { it.accounts.reliable("checkout", durable = true) }

            val lives = banks.map { it.cluster.life }
            val expected = lives.associate { life -> "account-checkout-$life" to Listed("account", life, life) }
            val oldest = checkNotNull(Placement.oldest(banks.first().cluster.view.members))
            val registry = checkNotNull(banks.single { it.cluster.self == oldest }.cluster.producers)
            withClue({ "the registry lists ${registry.listed}" }) {
                registry.await(30.seconds) { it == expected } shouldBe true
            }
        } finally {
            banks.forEach(Bank::close)
        }
    }

    @Test
    fun `a crashed node's commands are delivered, though the node that resumed them crashes straight after`() {
        val journal = Conflicts(JdbcJournal(accountsDatabase()))
        val ports = List(4) { ServerSocket(0).use(ServerSocket::getLocalPort) }
        val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
        // Accounts run only on a ledger, and none is up until both crashes, so nothing is confirmed before them. A
        // side of one stays, so the last of the three goes on alone.
        val bank = { i: Int ->
            val downing = Downing.staticQuorum(1, stableAfter = 3.seconds)
            Bank("b${i + 1}", ports[i], seeds, journal, Duration.ZERO, "ledger", ledger = i == 3, downing = downing)
        }
        val banks = (0 until 3).map(bank).toMutableList()
        val ids = List(20) { "a-$it" }
        try {
            banks.forEach { b ->
                b.cluster.await(1.minutes) { view -> view.members.count { it.status == Status.Up } == 3 } shouldBe true
            }
            val payments = banks.mapIndexed { i, b ->
                b.accounts.reliable("checkout", durable = true).also { payments ->
                    ids.forEach { id -> payments.send(id) { PayInto(i + 1, it) } shouldBe Unit.right() }
                }
            }
            val oldest = checkNotNull(Placement.oldest(banks.first().cluster.view.members))
            val resumer = banks.single { it.cluster.self == oldest }
            val (stays, crashing) = banks - resumer
            fun outbox(bank: Bank) = "account-checkout-${bank.cluster.life}"
            val outboxes = banks.associateWith(::outbox)

            crashing.close()
            val resumed = checkNotNull(resumer.cluster.producers)
            resumed.await(30.seconds) { it?.get(outbox(crashing))?.runner == resumer.cluster.life } shouldBe true
            resumer.close()
            banks += bank(3)

            payments[banks.indexOf(stays)].drain(1.minutes) shouldBe true
            val registry = checkNotNull(stays.cluster.producers)
            withClue({ "the registry lists ${registry.listed}" }) {
                registry.await(30.seconds) { it?.keys == setOf(outbox(stays)) } shouldBe true
            }
            ids.forEach { id -> journal.events(accountOf(id), paidPence).sorted() shouldBe listOf(1, 2, 3) }
            journal.seen.shouldBeEmpty()
            // A retired outbox keeps only its newest event, which the journal needs to check the next append.
            listOf(resumer, crashing).forEach { gone ->
                journal.read(PersistenceId("lark-producer", outboxes.getValue(gone))).size shouldBe 1
            }
        } finally {
            banks.forEach(Bank::close)
        }
    }
}
