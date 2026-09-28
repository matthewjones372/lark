package io.github.matthewjones372.lark.actor.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Producer
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.delivered
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.app.liquibase.migrate
import io.github.matthewjones372.lark.cluster.Sharded
import io.github.matthewjones372.lark.cluster.sharding
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Props
import org.apache.pekko.actor.typed.delivery.ConsumerController
import org.apache.pekko.actor.typed.delivery.DurableProducerQueue
import org.apache.pekko.actor.typed.javadsl.AskPattern
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.delivery.ShardingConsumerController
import org.apache.pekko.cluster.sharding.typed.delivery.ShardingProducerController
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey
import org.apache.pekko.persistence.typed.delivery.EventSourcedProducerQueue
import org.apache.pekko.persistence.typed.javadsl.CommandHandler
import org.apache.pekko.persistence.typed.javadsl.EventHandler
import org.apache.pekko.persistence.typed.javadsl.EventSourcedBehavior
import org.h2.jdbcx.JdbcDataSource
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.sql.DriverManager
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration
import org.apache.pekko.actor.typed.ActorRef as PekkoRef
import org.apache.pekko.persistence.typed.PersistenceId as PekkoPersistenceId

/** Pekko's own default pool for its journal; lark's Hikari pool on each node is given the same. */
private const val POOL = 20

/** One user for both sides: H2 refuses a login as anyone but the user that created the database. */
private const val H2_USER = "sa"

/** Counted down by the entity that handles a durable send: every node is in this JVM. */
private val handled = AtomicReference(CountDownLatch(0))

private fun h2(name: String) = "jdbc:h2:mem:$name-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

/** Applies [resource]'s DDL, found beside [owner], to the database at [url]. */
private fun migrate(url: String, owner: Class<*>, resource: String) {
    val ddl = checkNotNull(owner.getResource(resource)) { "no $resource on the classpath" }.readText()
    DriverManager.getConnection(url, H2_USER, "").use { connection ->
        connection.createStatement().use { it.execute(ddl) }
    }
}

private fun larkAccount(node: String, id: String) = persistent<Account, Long, Long>(
    id = PersistenceId("account", id),
    empty = 0,
    codec = deposited,
    command = { _, balance, command ->
        when (command) {
            is Deposit -> persist(command.pence).then { after -> command.reply(after) }
            is Locate -> none().then { command.reply(node) }
        }
    },
    event = { balance, pence -> balance + pence },
)

private fun larkWallet(node: String) = delivered(
    behaviour<Paying, Unit>(Unit) { _, _, message ->
        when (message) {
            is Payment -> handled.get().countDown()
            is Locate -> message.reply(node)
        }
        stay()
    },
)

/** One lark node's part: its journal's pool, the persistent accounts, and the wallets sent to reliably. */
internal class LarkLedgerNode(val pool: HikariDataSource, val accounts: Sharded<Account>, val wallets: Sharded<Paying>)

/**
 * Three lark nodes on one H2 in memory, each through its own pool: persistent accounts, and wallets that a producer
 * on the first node sends to reliably, in memory and durably. The entities measured are reached from the first node.
 */
@State(Scope.Benchmark)
open class LarkLedger {
    @Param("local", "remote")
    var owner: String = "local"

    internal lateinit var account: ActorRef<Account>
    internal lateinit var wallet: String
    internal lateinit var reliable: Producer<Paying>
    internal lateinit var durable: Producer<Paying>
    private lateinit var nodes: LarkTrio<LarkLedgerNode>

    @Setup(Level.Trial)
    fun start() {
        val url = h2("lark")
        migrate(JdbcDataSource().apply { setURL(url); user = H2_USER }, "lark/journal/jdbc/h2.sql")
        nodes = LarkTrio { cluster, name ->
            val pool = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = H2_USER
                    maximumPoolSize = POOL
                },
            )
            journal(JdbcJournal(pool))
            LarkLedgerNode(
                pool,
                cluster.sharding("account", accountCodec, passivateAfter = 1.hours) { id -> larkAccount(name, id) },
                cluster.sharding("wallet", payingCodec, passivateAfter = 1.hours) { larkWallet(name) },
            )
        }
        val near = nodes.parts.first()
        val within = ASK_WITHIN.toKotlinDuration()
        account = near.accounts.entity(
            idOwnedBy(owner(owner)) { id ->
                near.accounts.entity(id).ask<Account, String>(within) { Locate(it) }.getOrNull().orEmpty()
            },
        )
        wallet = idOwnedBy(owner(owner)) { id ->
            near.wallets.entity(id).ask<Paying, String>(within) { Locate(it) }.getOrNull().orEmpty()
        }
        reliable = near.wallets.reliable("reliable")
        durable = near.wallets.reliable("durable", durable = true)
    }

    @TearDown(Level.Trial)
    fun stop() {
        nodes.close()
        nodes.parts.forEach { it.pool.close() }
    }
}

/** An account remembered by its deposits, as `larkAccount` is. */
class PekkoAccountEntity(id: PekkoPersistenceId, private val node: String) :
    EventSourcedBehavior<PekkoAccount, PekkoDeposited, Long>(id) {

    override fun emptyState(): Long = 0

    override fun commandHandler(): CommandHandler<PekkoAccount, PekkoDeposited, Long> =
        newCommandHandlerBuilder().forAnyState()
            .onCommand(PekkoDeposit::class.java) { _, deposit ->
                Effect().persist(PekkoDeposited(deposit.pence)).thenReply(deposit.replyTo) { after -> after }
            }
            .onCommand(PekkoLocate::class.java) { _, locate -> Effect().reply(locate.replyTo, node) }
            .build()

    override fun eventHandler(): EventHandler<Long, PekkoDeposited> =
        newEventHandlerBuilder().forAnyState()
            .onEvent(PekkoDeposited::class.java) { balance, paid -> balance + paid.pence }
            .build()
}

/** A wallet behind Pekko's consumer controller: it confirms each command once handled, as `delivered` does. */
private fun pekkoWallet(
    node: String,
    start: PekkoRef<ConsumerController.Start<PekkoPaying>>,
): Behavior<ConsumerController.Delivery<PekkoPaying>> = Behaviors.setup { ctx ->
    start.tell(ConsumerController.Start(ctx.self))
    Behaviors.receiveMessage { delivery ->
        when (val message = delivery.message()) {
            is PekkoPay -> handled.get().countDown()
            is PekkoLocate -> message.replyTo.tell(node)
        }
        delivery.confirmTo().tell(ConsumerController.confirmed())
        Behaviors.same()
    }
}

/**
 * A `ShardingProducerController` on [system], and the actor that takes its demand: each `RequestNext` is room for one
 * command, which [next] hands to the benchmark's thread.
 */
internal class PekkoProducer(
    system: ActorSystem<*>,
    region: PekkoRef<ShardingEnvelope<ConsumerController.SequencedMessage<PekkoPaying>>>,
    id: String,
    queue: Behavior<DurableProducerQueue.Command<PekkoPaying>>?,
) {
    private val demand = LinkedBlockingQueue<ShardingProducerController.RequestNext<PekkoPaying>>()

    init {
        val controller = system.systemActorOf(
            ShardingProducerController.create(PekkoPaying::class.java, id, region, Optional.ofNullable(queue)),
            "producer-$id",
            Props.empty(),
        )
        val producer = system.systemActorOf(
            Behaviors.receiveMessage<ShardingProducerController.RequestNext<PekkoPaying>> { next ->
                demand.put(next)
                Behaviors.same()
            },
            "demand-$id",
            Props.empty(),
        )
        controller.tell(ShardingProducerController.Start(producer))
    }

    fun next(): ShardingProducerController.RequestNext<PekkoPaying> =
        checkNotNull(demand.poll(ASK_WITHIN.toMillis(), TimeUnit.MILLISECONDS)) { "the producer asked for nothing" }
}

/** Pekko Persistence JDBC on the H2 at [url], through Slick's Hikari pool, which is [POOL] connections by default. */
private fun journalOn(url: String) = """
    pekko.persistence.journal.plugin = "jdbc-journal"
    pekko.persistence.snapshot-store.plugin = "jdbc-snapshot-store"
    pekko-persistence-jdbc.shared-databases.slick {
      profile = "slick.jdbc.H2Profile${'$'}"
      db {
        url = "$url"
        driver = "org.h2.Driver"
        user = "$H2_USER"
        password = ""
        numThreads = $POOL
        maxConnections = $POOL
        minConnections = $POOL
      }
    }
    jdbc-journal.use-shared-db = "slick"
    jdbc-snapshot-store.use-shared-db = "slick"
    jdbc-read-journal.use-shared-db = "slick"
""".trimIndent()

/**
 * Three Pekko nodes on one H2 in memory, each through its own Slick pool: persistent accounts, and wallets that a
 * `ShardingProducerController` on the first node sends to, with and without a durable queue.
 */
@State(Scope.Benchmark)
open class PekkoLedger {
    @Param("local", "remote")
    var owner: String = "local"

    lateinit var account: EntityRef<PekkoAccount>
    lateinit var wallet: String
    internal lateinit var reliable: PekkoProducer
    internal lateinit var durable: PekkoProducer
    lateinit var system: ActorSystem<*>
    private lateinit var nodes: PekkoTrio

    @Setup(Level.Trial)
    fun start() {
        val url = h2("pekko")
        migrate(url, EventSourcedProducerQueue::class.java, "/schema/h2/h2-create-schema.sql")
        nodes = PekkoTrio(STILL + journalOn(url))
        system = nodes.systems.first()
        val accounts = EntityTypeKey.create(PekkoAccount::class.java, "account")

        @Suppress("UNCHECKED_CAST")
        val wallets = EntityTypeKey.create(
            ConsumerController.SequencedMessage::class.java as Class<ConsumerController.SequencedMessage<PekkoPaying>>,
            "wallet",
        )
        val regions = nodes.systems.mapIndexed { i, node ->
            val sharding = ClusterSharding.get(node)
            val name = nodeName(i)
            sharding.init(
                Entity.of(accounts) { PekkoAccountEntity(PekkoPersistenceId.of("account", it.entityId), name) },
            )
            sharding.init(
                Entity.of(wallets) { ShardingConsumerController.create { start -> pekkoWallet(name, start) } },
            )
        }
        val near = ClusterSharding.get(system)
        val id = idOwnedBy(owner(owner)) { id ->
            near.entityRefFor(accounts, id).ask<String>({ PekkoLocate(it) }, ASK_WITHIN).toCompletableFuture().join()
        }
        account = near.entityRefFor(accounts, id)
        reliable = PekkoProducer(system, regions.first(), "reliable", null)
        durable = PekkoProducer(
            system,
            regions.first(),
            "durable",
            EventSourcedProducerQueue.create(PekkoPersistenceId.ofUniqueId("durable-producer")),
        )
        wallet = idOwnedBy(owner(owner)) { id ->
            AskPattern.ask(
                reliable.next().sendNextTo(),
                { reply: PekkoRef<String> -> ShardingEnvelope(id, PekkoLocate(reply) as PekkoPaying) },
                ASK_WITHIN,
                system.scheduler(),
            ).toCompletableFuture().join()
        }
    }

    @TearDown(Level.Trial)
    fun stop() = nodes.close()
}

/**
 * One command to a persistent entity from the first node, which writes one event and answers once it is written: an
 * append and its round trip. lark: `ask` on a sharded `persistent`, answering in `then`, on `JdbcJournal`. Pekko:
 * `EntityRef.ask` on a sharded `EventSourcedBehavior`, answering in `thenReply`, on Persistence JDBC.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class PersistentAppendBenchmark {

    @Benchmark
    fun lark(ledger: LarkLedger): Long =
        checkNotNull(ledger.account.ask<Account, Long>(ASK_WITHIN.toKotlinDuration()) { Deposit(1, it) }.getOrNull())

    @Benchmark
    fun pekko(ledger: PekkoLedger): Long =
        ledger.account.ask<Long>({ PekkoDeposit(1, it) }, ASK_WITHIN).toCompletableFuture().join()
}

/**
 * One command sent reliably from the first node to an in-memory entity, until the producer has its confirmation.
 * lark: `reliable(...).send`, then `drain` for the confirmation. Pekko: `ShardingProducerController`'s
 * `MessageWithConfirmation`, whose `Done` comes once the consumer's confirmation reaches the producer.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class ReliableSendBenchmark {

    @Benchmark
    fun lark(ledger: LarkLedger) {
        checkNotNull(ledger.reliable.send(ledger.wallet) { Payment(1, it) }.getOrNull()) { "the producer was full" }
        check(ledger.reliable.drain(ASK_WITHIN.toKotlinDuration())) { "no confirmation came" }
    }

    @Benchmark
    fun pekko(ledger: PekkoLedger): Done = AskPattern.ask(
        ledger.reliable.next().askNextTo(),
        { done: PekkoRef<Done> ->
            ShardingProducerController.MessageWithConfirmation(ledger.wallet, PekkoPay(1) as PekkoPaying, done)
        },
        ASK_WITHIN,
        ledger.system.scheduler(),
    ).toCompletableFuture().join()
}

/**
 * One command sent durably from the first node to an in-memory entity, until the entity has handled it. Each side's
 * producer writes the command to its journal before sending it, and a confirmation after. lark:
 * `reliable(durable = true).send`. Pekko: `ShardingProducerController` with an `EventSourcedProducerQueue`. The row
 * ends at the entity, since Pekko answers a durable send once it is stored, not once it is confirmed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class DurableSendBenchmark {

    @Benchmark
    fun lark(ledger: LarkLedger) = awaitHandled {
        checkNotNull(ledger.durable.send(ledger.wallet) { Payment(1, it) }.getOrNull()) { "the producer was full" }
    }

    @Benchmark
    fun pekko(ledger: PekkoLedger) = awaitHandled {
        ledger.durable.next().sendNextTo().tell(ShardingEnvelope(ledger.wallet, PekkoPay(1)))
    }

    private fun awaitHandled(send: () -> Unit) {
        val done = CountDownLatch(1)
        handled.set(done)
        send()
        check(done.await(ASK_WITHIN.toMillis(), TimeUnit.MILLISECONDS)) { "the entity never handled the send" }
    }
}
