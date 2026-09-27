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

What comes next on one node, finding actors, remembering state and testing,
is being written; the [cluster guide](cluster.md) takes over where a second
node comes in.
