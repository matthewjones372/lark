package io.github.matthewjones372.lark.cluster

import arrow.core.right
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.remote.Node
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
}
