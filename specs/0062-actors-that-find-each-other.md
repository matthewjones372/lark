# 0062 — Actors that find each other

## Problem

A `lark-actor` actor can only reach another through a ref someone handed it.
Spreading work over four workers means spawning four and writing the
round-robin by hand, and the same key reaching the same worker means writing
the hash by hand too. An actor that wants "whoever handles adoptions" has to be
given that ref at construction, so everything is wired in `main` in dependency
order. A message told to an actor that has stopped, or one its step answers
with `unhandled()`, disappears without a trace, so a typo'd protocol or a race
with a stop looks like a hang. An application built with `lark-app` cannot
declare an actor as a node at all: `lark-app-pekko` can, for Pekko's.

0059 put routers, the receptionist, dead letters and `lark-app` nodes off to
here.

## Not doing

- **Anything across processes.** One flock, one process. The keys and refs
  here are shaped so that 0064–0066 (transport, membership, sharding) can
  extend them, and no further.
- **A balancing pool with a shared mailbox, and a resizer.** A pool has the
  size it is given.
- **Broadcast, scatter-gather and tail-chopping routes.** A route picks one
  routee; the rest can be written on `group` if asked.
- **Dead letters that are kept.** They are handed to a handler and gone.
- **A full mailbox going to dead letters.** A `tell` from inside a step into a
  full mailbox still fails the step, as 0059 has it.

## Shape

```kotlin
// A pool: one ref, four workers behind it, each restarted on its own.
val resizers = ctx.spawn("resizers", pool(4, restart = Schedule.recurs(3)) { resizer() })

// The same pet always reaches the same worker.
val ledgers = ctx.spawn("ledgers", pool(8, route = hashing { it: Ledger -> it.petId }) { ledger() })

// Refs already running, as one: no actor in between, so no hop.
val replicas: ActorRef<Query> = group(a, b, c)

// The receptionist: found by a typed key rather than handed over.
val Adoptions = ServiceKey<Adoption>("adoptions")

val desk = behaviour<Adoption, Desk>(Desk.Open) { ctx, desk, message -> … }
    .onStart { ctx -> ctx.register(Adoptions) }         // listed until it stops

val clerk = behaviour<Clerk, Known>(Known.None) { ctx, known, message ->
    when (message) {
        Start -> { ctx.subscribe(Adoptions) { Listing(it) }; stay() }   // told now, and on every change
        is Listing -> become(Known.Desks(message.refs))
        …
    }
}

// Dead letters: a message nobody handled, and why.
flock<Nothing, Unit> {
    onDeadLetter { letter -> logWarn("dead letter: $letter") }
    …
}

// In a test they are a list, so a lost message is an assertion rather than a hang.
testActors {
    val desk = spawn("desk", desk())
    desk.send(Close)
    desk.send(Adopt(sam))
    deadLetters shouldBe listOf(DeadLetter(desk.address, Adopt(sam), DeadLetter.Why.Stopped))
}

// lark-app-actor: actors as nodes, keyed by the ref of their protocol.
val app = actors() + actor<Adoption, AdoptionRepository>("desk") { repo -> desk(repo) }
app.use { desk: ActorRef<Adoption> -> … }
```

- **Pools.** `pool(size, route = roundRobin(), restart) { behaviour }` is a
  behaviour: a router actor that spawns `size` children on its first message
  and forwards each message to one. A routee whose mailbox is full is passed
  over for the next, and a step whose every routee is full fails. `restart` is
  the pool's, not the spawn's, since a pool cannot read its own spawn's
  schedule; it applies to each routee on its own, so one failing worker
  restarts without the others; one that stops for good leaves the pool
  smaller, and a pool with none left stops. Routes are `roundRobin()` and
  `hashing(key)`.
- **Groups.** `group(refs)` is an `ActorRef` over refs that are already
  running: a `tell` picks one by its route on the caller's thread, with no
  actor between.
- **The receptionist.** `ServiceKey<M>(id)` names a protocol. `ctx.register`
  lists `self` under a key until it stops, and `ctx.subscribe(key) { listing
  -> message }` tells the actor a message of its own type now and whenever the
  listing changes. Code outside an actor has `Flock.find(key)`. One
  receptionist per flock. A behaviour registers from
  `Behaviour.onStart { ctx -> }`, which runs before the first message and
  again after each restart, since a restart loses the registration with the
  rest of the actor.
- **Dead letters.** `DeadLetter(recipient, message, why)`, `why` being
  `Stopped` or `Unhandled`: a message told to a stopped actor, or one its step
  answers `unhandled()`. A full mailbox is not one; it still fails the step,
  so a backpressure bug stays a failure rather than a log line. A flock hands each to the handler set with
  `onDeadLetter`, which by default logs it at debug through lark's `Logger`;
  it is `lark-actor`'s, as an extension, so `lark`'s `flock` does not change. `.test()` and
  `testActors` keep them in `deadLetters`, in order.
- **`lark-app-actor`.** `actors()` is a node holding a flock for the
  application's life, opened on a thread of its own that waits until release,
  since `lark-app`'s resource scope has no forks to lend; every actor still
  lives in a flock and `lark` needs nothing new, and releasing it closes the
  flock, stopping every actor after its running step. `actors(onDeadLetter)`
  sets the flock's dead-letter handler. `Flock.stop(ref)` is new in
  `lark-actor`, so a node can stop its one actor and wait for it. `actor<M, D…>(name) { deps -> behaviour }` is a node
  keyed by `ActorRef<M>`, as in `lark-app-pekko`, stopped before anything it
  depends on is released.

## Why this shape

A pool is an actor because it owns its routees: it spawns them, supervises
them and stops them, and that is what an actor already does, so a pool is
children plus a forwarding step and nothing new in the runtime. The cost is
one hop per message, which is why `group` exists for refs that need no owner.
The receptionist is keyed by a typed `ServiceKey<M>`, not a name, so a lookup
answers `ActorRef<M>` without a cast, and a listing is a message rather than a
callback, so it is in order with everything else the actor handles. Dead
letters go to a handler rather than an actor so a flock with none still logs
them, and a test sees them as a list because the test kit already runs to idle
and can say "this was lost" exactly once.

## Stack

- [x] **`spec-0062-dead-letters`** ([#129](https://github.com/matthewjones372/lark/pull/129)) — `DeadLetter`, the flock's handler, the
      test kit's list. Done when: a message to a stopped actor and one
      answered `unhandled()` each arrive once, on both runtimes.
- [x] **`spec-0062-receptionist`** ([#130](https://github.com/matthewjones372/lark/pull/130)) — `ServiceKey`, `register`, `subscribe`,
      `Flock.find`, and `onStart` to register from. Done when: a subscriber is told a listing when an actor
      registers and again when it stops, on both runtimes.
- [x] **`spec-0062-routers`** ([#131](https://github.com/matthewjones372/lark/pull/131)) — `pool` with `roundRobin` and `hashing`, and
      `group`. Done when: a pool of four hands eight messages two to each, one
      key always reaches one routee, and a failed routee restarts alone.
- [x] **`spec-0062-app`** ([#132](https://github.com/matthewjones372/lark/pull/132)) — `lark-app-actor`: `actors()` and `actor<M>()`.
      Done when: an application with an actor node starts, and on the way out
      the actor has stopped before the node it depends on is released.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Nothing. All five were answered as recommended and are in Shape: stopped and
unhandled messages are dead letters and a full mailbox is not, a flock hands
them to a handler, a pool is an actor with `group` as the ref, `onStart` runs
before the first message and after each restart, and `actors()` holds its
flock on a thread of its own, since the application's scope has no forks to
lend.
