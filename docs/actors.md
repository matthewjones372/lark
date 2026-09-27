# Actors on one node

> Lark is a scratchpad, not a finished library — see
> [what this is](../README.md#what-this-is). Everything here works and is tested;
> none of it is settled.

`lark-actor` is an actor as a value: a state, and a step that takes one
message at a time. This page is what a service writes with it in one process,
in the order it meets it. Each section names the spec that argued for the
design, for the reasoning this page leaves out. A second node, a cluster and
entities spread across it are the [cluster guide](cluster.md)'s.

Every complete example below is compiled against the library by
`ActorsGuideTest`, so what you read is what builds.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:lark-actor:0.1.0")
}
```

1. [An actor](#an-actor)
2. [Failure](#failure)
3. [Time](#time)
4. [Finding and routing](#finding-and-routing)
5. [Remembering](#remembering)
6. [Testing](#testing)

## An actor

A `behaviour` is where an actor starts and what one message does to it
([spec 0059](../specs/0059-an-actor-without-an-actor-system.md)). A step gets
the actor's context, its state and one message, and answers what happens next:

- `stay()` keeps the state;
- `become(state)` replaces it;
- `stop()` ends the actor;
- `unhandled()` keeps the state and sends the message where dead letters go.

Nothing runs until the behaviour is spawned. An actor lives in a `flock`, the
structured scope `lark` already has, and cannot outlive it: closing the flock
stops every actor in it, after any step already running has returned. Each
actor has a virtual thread when it has work and none when it has not, so a step
may block on JDBC or a client with no async API.

`tell` sends and returns. `ask` sends a message carrying a `Reply` and waits up
to a timeout for the answer, which comes back as an `Either`: an actor that
stopped, or took too long, is a value rather than an exception.

<!-- actors-actor -->
```kotlin
import arrow.core.Either
import io.github.matthewjones372.lark.actor.AskFailure
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.stop
import io.github.matthewjones372.lark.actor.unhandled
import io.github.matthewjones372.lark.flock
import kotlin.time.Duration.Companion.seconds

sealed interface Till

data class Ring(val pence: Int) : Till

data class Total(val reply: Reply<Int>) : Till

/** Takes nothing back: the till answers `unhandled()`, and a refund is a dead letter. */
data class Refund(val pence: Int) : Till

data object Close : Till

fun till() = behaviour<Till, Int>(0) { _, total, message ->
    when (message) {
        is Ring -> become(total + message.pence)
        is Total -> stay().also { message.reply(total) }
        is Refund -> unhandled()
        Close -> stop()
    }
}

fun main() {
    val total: Either<Nothing, Either<AskFailure, Int>> = flock {
        val till = spawn("till", till())
        till.tell(Ring(250))
        till.tell(Ring(120))
        till.ask(1.seconds) { Total(it) }
    }
    println(total) // Either.Right(Either.Right(370))
}
```

A step runs one message at a time, so the state needs no lock. Read it and
answer with a new one; never share a mutable one with anything outside the
actor.

## Failure

A step fails in one of two ways
([spec 0060](../specs/0060-an-actor-that-fails-and-is-watched.md)):

- **It raises** a failure it declared. A behaviour names its failure type as
  its third type parameter, `behaviour<M, S, E>`, and its step raises with
  Arrow's `raise`. A behaviour that never raises is a
  `Behaviour<M, S, Nothing>`.
- **It throws**, for what nobody declared: a bug, or a dependency that broke.

Either way, the actor's `restart` schedule decides. While the schedule
continues, the actor starts again from its initial state after the schedule's
delay, with the messages still in its mailbox. Once the schedule is done, or if
there is none, the actor stops. The schedule is `lark`'s `Schedule`, the same
one a retry takes, and it sees each failure as a `Failure.Raised` or a
`Failure.Thrown`.

Signals are what happens to an actor rather than what is sent to it:
`Signal.Stopping` as it ends, and `Signal.Terminated` when an actor it watches
has stopped. `onSignal` takes them beside the step, and `onStart` runs before
the first message and again after each restart.

An actor spawns children with `ctx.spawn`. A child is stopped before its parent
stops or restarts, so a parent that restarts has no children until its step
spawns them again. `ctx.watch` asks to hear when another actor stops, child or
not; `watch` on the flock gives the same as a `Deferred` to await.

<!-- actors-failure -->
```kotlin
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Failure
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onSignal
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.watch
import io.github.matthewjones372.lark.logInfo
import kotlin.time.Duration.Companion.milliseconds

sealed interface Feed

data class Price(val sku: String, val pence: Int) : Feed

data class Quote(val sku: String, val reply: Reply<Int>) : Feed

/** What the price feed declares it can fail with. */
data class BadPrice(val sku: String, val pence: Int)

fun prices() = behaviour<Feed, Map<String, Int>, BadPrice>(emptyMap()) { _, prices, message ->
    when (message) {
        is Price -> {
            if (message.pence < 0) raise(BadPrice(message.sku, message.pence))
            become(prices + (message.sku to message.pence))
        }

        // An unknown sku throws: nobody declared it, and the schedule sees it as Failure.Thrown.
        is Quote -> stay().also { message.reply(prices.getValue(message.sku)) }
    }
}.onStart { ctx -> logInfo("${ctx.self.address.path} starts with no prices") }

/** Up to five restarts, each waiting twice as long as the last; then the feed stops. */
val backingOff: Schedule<Failure<BadPrice>, *> =
    Schedule.exponential<Failure<BadPrice>>(100.milliseconds) zipLeft Schedule.recurs(5)

/** One feed per region, each a child: a feed that stops is forgotten, and started again when next asked for. */
fun feeds() = behaviour<Pair<String, Feed>, Map<String, ActorRef<Feed>>>(emptyMap()) { ctx, feeds, (region, feed) ->
    val running = feeds[region] ?: ctx.spawn("prices-$region", prices(), restart = backingOff).also(ctx::watch)
    running.tell(feed)
    if (region in feeds) stay() else become(feeds + (region to running))
}.onSignal { _, feeds, signal ->
    if (signal is Signal.Terminated) become(feeds.filterValues { it != signal.ref }) else stay()
}

/** Watching from outside an actor: the flock's own watch, awaited. */
fun Flock<Nothing>.untilStopped(feed: ActorRef<Feed>): Signal.Terminated = watch(feed).await()
```

A raise that belongs to an `either { }` inside the step is that `either`'s,
and never mistaken for the actor's own, so a step can use Arrow as it would
anywhere else.

## Time

An actor's timers send it messages later, under a key
([spec 0061](../specs/0061-an-actor-on-a-clock.md)):
`ctx.timers.after` once, `ctx.timers.every` repeatedly, and `ctx.timers.cancel`
to take one back. Starting a key that is running replaces it, and a message
from a cancelled timer never arrives, even one already in the mailbox. A
restart cancels them all, which is why timers are usually started in `onStart`.

A receive timeout, `ctx.receiveTimeout`, sends a message once nothing has
arrived for a while. A state's own timers, `ctx.become(state) { after(…) }`,
end when the actor becomes a state of another class, so a timeout that belongs
to "waiting for payment" cannot fire once payment has arrived.

Every wait runs on the flock's clock, which is `lark`'s `clock`. Bind a
`TestClock` around the flock and move it with `adjust`, and an hour's timers
fire in no time at all.

<!-- actors-time -->
```kotlin
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.awaitIdle
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.stop
import io.github.matthewjones372.lark.actor.unhandled
import io.github.matthewjones372.lark.actor.watch
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

sealed interface Basket

data class Add(val sku: String) : Basket

data object Checkout : Basket

data object Paid : Basket

data object Remind : Basket

data object Abandon : Basket

sealed interface Stage

data class Shopping(val items: List<String>) : Stage

data class Paying(val items: List<String>) : Stage

fun basket(shopper: ActorRef<String>) = behaviour<Basket, Stage>(Shopping(emptyList())) { ctx, stage, message ->
    when {
        message == Remind -> stay().also { shopper.tell("your basket is waiting") }
        message == Abandon -> stop()
        stage is Shopping && message is Add -> become(Shopping(stage.items + message.sku))
        // Payment has ten minutes. The timer is the Paying state's, so it cannot fire once the basket moves on.
        stage is Shopping && message == Checkout -> ctx.become(Paying(stage.items)) { after(10.minutes, Abandon) }
        stage is Paying && message == Paid -> stop()
        else -> unhandled()
    }
}.onStart { ctx ->
    ctx.timers.every("remind", 1.hours, Remind)
    ctx.receiveTimeout(2.hours, Abandon)
}

fun main() {
    val moving = TestClock()
    clock.locally(moving) {
        flock<Nothing, Unit> {
            val shopper = spawn("shopper", behaviour<String, Unit>(Unit) { _, _, note -> stay().also { println(note) } })
            val basket = spawn("basket", basket(shopper))
            basket.tell(Add("sku-1"))
            basket.tell(Checkout)
            awaitIdle()

            // Ten minutes pass with no payment, in no time: the basket is abandoned.
            moving.adjust(10.minutes)
            watch(basket).await()
        }
    }
}
```

## Finding and routing

An actor that should be found registers under a `ServiceKey`, a protocol by
name ([spec 0062](../specs/0062-actors-that-find-each-other.md)).
`ctx.register` lists it until it stops or restarts; `find` on the flock answers
the refs listed now; and `ctx.subscribe` tells an actor the listing now and
again each time it changes, as a message of its own protocol. A registration
is lost on a restart, like a timer, so it belongs in `onStart`.

Two routers spread messages over several actors:

- **`group`** makes one ref from refs already running. A tell picks one on the
  caller's thread, with no actor between.
- **`pool`** is an actor that spawns its routees as its children and passes each
  message to one of them. A routee that fails restarts on its own, and one
  whose mailbox is full is passed over.

Both take a `Route`: `roundRobin()` by default, or `hashing { … }`, so one key
always reaches one routee.

`entities` runs one actor per id, as children of one manager: started on the
id's first message, and stopped once it has had nothing for a while. A ref from
`entity(id)` stays good while the entity comes and goes. It is sharding on one
node, and the [cluster guide](cluster.md#entities) spreads the same thing over
many.

<!-- actors-routing -->
```kotlin
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.ServiceKey
import io.github.matthewjones372.lark.actor.awaitIdle
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.entities
import io.github.matthewjones372.lark.actor.entity
import io.github.matthewjones372.lark.actor.find
import io.github.matthewjones372.lark.actor.group
import io.github.matthewjones372.lark.actor.hashing
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.pool
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stay
import kotlin.time.Duration.Companion.minutes

data class Job(val owner: String, val document: String)

val Printers = ServiceKey<Job>("printers")

fun printer() = behaviour<Job, Int>(0) { _, printed, _ -> become(printed + 1) }
    .onStart { ctx -> ctx.register(Printers) }

sealed interface Spool

data class Print(val job: Job) : Spool

data class Listing(val printers: Set<ActorRef<Job>>) : Spool

/** Follows the printers as they come and go, and hands each job to one of those listed now. */
fun spooler() = behaviour<Spool, Set<ActorRef<Job>>>(emptySet()) { _, printers, message ->
    when (message) {
        is Listing -> become(message.printers)
        is Print -> stay().also { printers.firstOrNull()?.tell(message.job) }
    }
}.onStart { ctx -> ctx.subscribe(Printers, ::Listing) }

fun Flock<Nothing>.office() {
    repeat(3) { spawn("printer-$it", printer()) }
    awaitIdle()

    // Refs already running, as one: each tell goes to the next printer in turn.
    val printers = group(find(Printers).toList())
    printers.tell(Job("ann", "report"))

    // Four printers of its own, and one owner's jobs always to the same one.
    val spool = spawn("pool", pool(4, hashing<Job> { it.owner }) { printer() })
    spool.tell(Job("bo", "invoice"))

    spawn("spooler", spooler()).tell(Print(Job("cy", "memo")))

    // One printer per desk, started on first use and stopped after half an hour idle.
    val desks = spawn("desks", entities(passivateAfter = 30.minutes) { _ -> printer() })
    desks.entity("desk-7").tell(Job("di", "letter"))
}
```

## Remembering

A `persistent` actor is remembered by its events
([spec 0063](../specs/0063-an-actor-that-is-remembered.md)). A command answers
an effect instead of a next state:

- `persist(events)` writes them to the journal, then applies them with `event`;
- `none()` writes nothing;
- either can be followed by `then { state -> … }`, which runs once the events
  are written and applied, which is where a reply goes.

On start, and after every restart, the actor replays its events before its
first command. Its state is a `Remembered`: the value the events built and the
number of the last one. An append that finds another writer has got there first
is raised as a `JournalConflict`, so supervision decides.

The journal and the snapshot store are the flock's. `InMemoryJournal` and
`InMemorySnapshots` keep everything in the process, for tests and for trying
things out. With `snapshots = every(n, codec)`, the actor saves its state every
`n` events and a start replays only what came after the newest save
([spec 0074](../specs/0074-a-recovery-that-does-not-replay-everything.md)).
A journal that outlives the node, on Postgres, and read models that follow it
are the [cluster guide](cluster.md#state-that-survives)'s.

<!-- actors-persistent -->
```kotlin
import arrow.core.Either
import io.github.matthewjones372.lark.actor.AskFailure
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.InMemoryJournal
import io.github.matthewjones372.lark.actor.InMemorySnapshots
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.StateCodec
import io.github.matthewjones372.lark.actor.ask
import io.github.matthewjones372.lark.actor.every
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.snapshots
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stop
import io.github.matthewjones372.lark.flock
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

sealed interface Account

data class Deposit(val pence: Long) : Account

data class Withdraw(val pence: Long, val reply: Reply<Boolean>) : Account

data class Balance(val reply: Reply<Long>) : Account

/** What happened to an account: a change to its balance, in pence. */
data class Moved(val pence: Long)

object MovedCodec : EventCodec<Moved> {
    override fun encode(event: Moved): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(event.pence).array()

    override fun decode(bytes: ByteArray): Moved = Moved(ByteBuffer.wrap(bytes).long)
}

object BalanceCodec : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(state).array()

    override fun decode(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).long
}

fun account(id: String) = persistent<Account, Moved, Long>(
    id = PersistenceId("account", id),
    empty = 0,
    codec = MovedCodec,
    command = { _, balance, command ->
        when (command) {
            is Deposit -> persist(Moved(command.pence))
            is Withdraw ->
                if (command.pence > balance) {
                    none().then { command.reply(false) }
                } else {
                    persist(Moved(-command.pence)).then { command.reply(true) }
                }
            is Balance -> none().then { command.reply(balance) }
        }
    },
    event = { balance, moved -> balance + moved.pence },
    snapshots = every(100, BalanceCodec),
)

fun main() {
    val balance: Either<Nothing, Either<AskFailure, Long>> = flock {
        journal(InMemoryJournal())
        snapshots(InMemorySnapshots())
        val first = spawn("a-1", account("a-1"))
        first.tell(Deposit(500))
        first.ask<Account, Boolean>(1.seconds) { Withdraw(200, it) }
        stop(first).await()

        // The same id, started again: it replays its events and carries on where the first left off.
        val again = spawn("a-1-again", account("a-1"))
        again.ask(1.seconds) { Balance(it) }
    }
    println(balance) // Either.Right(Either.Right(300))
}
```

## Testing

Everything above runs without threads, stepped by the test itself
([spec 0059](../specs/0059-an-actor-without-an-actor-system.md)).
`behaviour.test()` runs one actor on the calling thread, and `testActors { }`
runs several that talk to each other. A tell from the test returns once that
message, and everything it caused, has been handled, so a test asserts straight
after it with nothing to wait for.

- **State and what went wrong.** A test actor shows its `state`, the messages it
  left `unhandled`, the `deadLetters` of the whole test, and why it stopped.
- **Restarts are recorded, not waited out.** Each restart's delay goes into
  `delays` and `restarts` counts them, so a test of a five-minute back-off takes
  no time.
- **Time moves when the test says.** `advance` moves the test's clock on and
  delivers every timer that falls due on the way, in order.
- **The journal is the test's.** `testActors` gives its actors an in-memory
  journal the test can read, and `restart()` replays it.

The tests below are run by the build, as well as compiled, so each passes.

<!-- actors-testing -->
```kotlin
import arrow.core.right
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Failure
import io.github.matthewjones372.lark.actor.Reply
import io.github.matthewjones372.lark.actor.become
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.stop
import io.github.matthewjones372.lark.actor.test
import io.github.matthewjones372.lark.actor.testActors
import io.github.matthewjones372.lark.actor.unhandled
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

sealed interface Till

data class Ring(val pence: Int) : Till

data class Total(val reply: Reply<Int>) : Till

data class Refund(val pence: Int) : Till

fun till() = behaviour<Till, Int, String>(0) { _, total, message ->
    when (message) {
        is Ring -> if (message.pence > 0) become(total + message.pence) else raise("rang ${message.pence}")
        is Total -> stay().also { message.reply(total) }
        is Refund -> unhandled()
    }
}

sealed interface Basket

data object Checkout : Basket

data object Paid : Basket

data object Abandon : Basket

fun basket(shopper: ActorRef<String>) = behaviour<Basket, Boolean>(false) { ctx, paying, message ->
    when {
        message == Checkout && !paying -> ctx.become(true) { after(10.minutes, Abandon) }
        message == Paid && paying -> stop()
        message == Abandon -> stop().also { shopper.tell("abandoned") }
        else -> unhandled()
    }
}

class TillTest {

    @Test
    fun `a till totals what it rang, and leaves a refund unhandled`() {
        val till = till().test()

        till.send(Ring(250))
        till.send(Refund(100))

        till.ask { Total(it) } shouldBe 250.right()
        till.unhandled shouldBe listOf(Refund(100))
    }

    @Test
    fun `a till that fails restarts empty, and each back-off is recorded rather than waited`() {
        val backingOff = Schedule.exponential<Failure<String>>(100.milliseconds) zipLeft Schedule.recurs(5)
        val till = till().test(restart = backingOff)

        till.send(Ring(250))
        till.send(Ring(-1))
        till.send(Ring(-2))

        till.state shouldBe 0
        till.delays shouldBe listOf(100.milliseconds, 200.milliseconds)
    }

    @Test
    fun `an unpaid basket is abandoned when ten minutes pass, and not before`() {
        testActors {
            val heard = mutableListOf<String>()
            val shopper = spawn("shopper", behaviour<String, Unit>(Unit) { _, _, note -> stay().also { heard += note } })
            val basket = spawn("basket", basket(shopper))

            basket.send(Checkout)
            advance(9.minutes)
            basket.stopped shouldBe false

            advance(1.minutes)
            basket.stopped shouldBe true
            heard shouldBe listOf("abandoned")
        }
    }
}
```

A test that needs real threads, a blocking client in a step say, runs the
same behaviours in a `flock` instead, with a `TestClock` for its time.

Where a second node comes in, the [cluster guide](cluster.md) takes over.
