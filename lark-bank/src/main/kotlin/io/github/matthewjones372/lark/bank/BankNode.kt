package io.github.matthewjones372.lark.bank

import arrow.core.Either
import io.github.matthewjones372.lark.actor.AskFailure
import io.github.matthewjones372.lark.actor.Full
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.Producer
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.remote.node
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.cluster.Cluster
import io.github.matthewjones372.lark.cluster.Discovery
import io.github.matthewjones372.lark.cluster.Downing
import io.github.matthewjones372.lark.cluster.Gossiping
import io.github.matthewjones372.lark.cluster.Member
import io.github.matthewjones372.lark.cluster.MemberEvent
import io.github.matthewjones372.lark.cluster.Sharded
import io.github.matthewjones372.lark.cluster.Status
import io.github.matthewjones372.lark.cluster.cluster
import io.github.matthewjones372.lark.cluster.sharding
import io.github.matthewjones372.lark.flock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How a node runs. The gossip is calm: brisk probes take a node busy with transfers for a node that has gone. */
internal data class Settings(
    val gossiping: Gossiping = Gossiping(probeEvery = 1.seconds, ackWithin = 500.milliseconds, formAfter = 2.seconds),
    val downAfter: Duration = 3.seconds,
    val resendAfter: Duration = 1.seconds,
    val passivateAfter: Duration = 2.minutes,
)

/** Commands a producer may keep unconfirmed: enough that a load never waits on one. */
private const val KEEP = 100_000

/** One node's two durable producers: debits and credits to accounts, and starts and answers to transfers. */
private class Outbox(val accounts: Producer<AccountMsg>, val transfers: Producer<TransferMsg>) {
    fun toAccount(id: String, command: AccountMsg) =
        check(accounts.send(id) { ToAccount(command, it) }.isRight()) { "no room to send $command to $id" }

    fun toTransfer(id: String, command: TransferMsg) =
        check(transfers.send(id) { ToTransfer(command, it) }.isRight()) { "no room to send $command to $id" }
}

private class Parts(
    val cluster: Cluster,
    val accounts: Sharded<AccountMsg>,
    val transfers: Sharded<TransferMsg>,
    val outbox: Outbox,
)

/**
 * One node of the bank, on a thread of its own until [close]: a cluster member with the accounts and transfers
 * sharded on it. Its producers are named by its incarnation, and when a member is removed, the oldest left starts
 * that member's producers again, to send what it had kept. [close] goes as a crash does: it leaves and drains nothing.
 */
internal class BankNode(
    val name: String,
    port: Int,
    seeds: Discovery,
    journal: Journal,
    settings: Settings = Settings(),
    ended: (String, Saga) -> Unit = { _, _ -> },
) : AutoCloseable {
    val members = Members()
    private val done = CountDownLatch(1)

    // Set before the flock closes, so a member removed meanwhile is not adopted by a node on its way out.
    @Volatile
    private var closing = false
    private val started = CompletableFuture<Parts>()
    private val thread = Thread.ofPlatform().name("bank-$name").start {
        flock<Nothing, Unit> {
            journal(journal)
            val downing = Downing.keepMajority(stableAfter = settings.downAfter)
            val cluster = cluster(node(name, port), seeds, settings.gossiping, downing, leaveWithin = Duration.ZERO)
            val outbox = CompletableFuture<Outbox>()
            val accounts = cluster.sharding("account", AccountCodec, settings.passivateAfter) { id ->
                account(id) { transfer, answer -> outbox.get().toTransfer(transfer, answer) }
            }
            val transfers = cluster.sharding("transfer", TransferCodec, settings.passivateAfter) { id ->
                transfer(id, { account, command -> outbox.get().toAccount(account, command) }, ended)
            }

            fun outboxOf(member: Member): Outbox {
                val id = "${member.node.name}-${member.uid.toULong().toString(radix = 36)}"
                return Outbox(
                    accounts.reliable(id, settings.resendAfter, KEEP, durable = true),
                    transfers.reliable(id, settings.resendAfter, KEEP, durable = true),
                )
            }
            val watcher = members.subscriber { event, seen ->
                val oldest = seen.up.firstOrNull()?.node == cluster.self
                if (event is MemberEvent.Removed && oldest && !closing) outboxOf(event.member)
            }
            cluster.subscribe(spawn("members", watcher))
            members.await(1.minutes) { seen -> seen.members[cluster.self]?.status == Status.Up }
            outbox.complete(outboxOf(members.seen.members.getValue(cluster.self)))
            started.complete(Parts(cluster, accounts, transfers, outbox.get()))
            done.await()
        }
    }

    private val parts: Parts get() = started.get(1, TimeUnit.MINUTES)

    fun open(account: String, pence: Long): Either<AskFailure, Long> =
        parts.accounts.entity(account).ask(ASK) { Open(pence, it) }

    fun balance(account: String): Either<AskFailure, Long> = parts.accounts.entity(account).ask(ASK) { Balance(it) }

    /** Starts the transfer [id] once its start is kept in this node's journal: from then on no crash loses it. */
    fun transfer(id: String, from: String, to: String, pence: Long): Either<Full, Unit> =
        parts.outbox.transfers.send(id) { ToTransfer(Start(from, to, pence), it) }

    fun status(id: String): Either<AskFailure, String> = parts.transfers.entity(id).ask(ASK) { Status(it) }

    override fun close() {
        closing = true
        done.countDown()
        thread.join()
    }

    private companion object {
        val ASK = 5.seconds
    }
}
