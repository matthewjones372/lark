# 0059 — An actor without an actor system

## Problem

An actor is lark's best tool for one writer over some state: the petshop's
`Shop` (`Adoptions.kt`) exists so two adoptions of one tortoise cannot both
succeed. Today that means Pekko. A step must not block, so a repository call
becomes `pipeToSelf`, an extra state and a stash. The test needs an
`ActorSystem`, probes and timed `expectMessage`. And a failure crosses into
`Throwable`, beside lark's declared errors. 0058 smooths the Kotlin surface but
cannot change any of that, because it is Pekko's runtime.

## Not doing

- **Lifecycle, time, topology:** signals, supervision, `watch`, children (0060);
  timers, receive timeout, stash (0061); routers, receptionist, dead letters and
  lark-app nodes (0062).
- **Keyed entities, persistence, transport, membership and sharding** (0063–0066).
  This spec only keeps them possible; see the last bullets of Shape.
- **Seeded interleavings.** `.test()` runs actors in a fixed order; exploring
  orders waits for 0042.
- **Interop with Pekko actors.** A bridge, if wanted, is its own spec.

## Shape

A module, `lark-actor`, on `lark` and nothing else.

```kotlin
sealed interface Shop
data class Arrived(val pet: Pet) : Shop
data class Find(val id: PetId, val reply: Reply<Option<Pet>>) : Shop

fun shop(repo: PetRepo): Behaviour<Shop, Shelf> =
    behaviour(Shelf.empty) { ctx, shelf, message ->
        when (message) {                                   // no else
            is Arrived -> become(shelf + repo.save(message.pet))   // blocking is fine
            is Find -> { message.reply(shelf[message.id]); stay() }
        }
    }

flock {
    val shop: ActorRef<Shop> = spawn("shop", shop(repo))
    shop.tell(Arrived(rex))
    shop.ask(within = 2.seconds) { Find(rex.id, it) }       // Either<AskFailure, Option<Pet>>
}

// in a test: no threads, no system, no waiting
val shop = shop(InMemoryPetRepo()).test()
shop.send(Arrived(rex))
shop.ask { Find(rex.id, it) } shouldBe Right(Some(rex))
shop.state shouldBe Shelf.of(rex)
```

- **The behaviour.** `Behaviour<M, S>` is a value: an initial state and a step
  `(Ctx<M>, S, M) -> Next<S>`. `Next` is `Stay`, `Become(s)`, `Stop` or
  `Unhandled`. A throw stops the actor for now; 0060 adds declared failures and
  supervision.
- **The runtime.** `spawn` holds the actor in its `Flock`, so it cannot outlive
  the scope. The mailbox is a lock-free multi-producer queue. An idle actor
  holds no thread. A message to an idle actor starts a virtual thread that runs
  up to `throughput` messages (default 5, as Pekko's), then parks or yields.
  There is no linger before parking unless the benchmark shows it pays. The
  activation is one compare-and-set state, as in 0037.
- **Bounded mailboxes.** Capacity is required. Only a `tell` from outside any
  actor waits while the mailbox is full. A `tell` from inside an actor takes an
  overflow policy, failing or going to dead letters, so two actors each
  waiting on the other's full mailbox cannot deadlock.
- **Replies and asks.** `ask` waits on the calling thread and answers
  `Either<AskFailure, A>`. `AskFailure` is `TimedOut`, `Stopped` or
  `Unreachable`. The last never happens locally, but adding it later would
  break every `when`. `Reply<A>` sends once, and `A : Any`.
- **Tests.** `.test()` runs the same behaviour on the calling thread. `send`
  and `ask` return once the message and everything it caused are handled.
  `told(ref)` lists what was sent, and a missing or doubled reply fails at
  once. `state` is readable, since it is an immutable value and not a hidden
  field. A test scope runs several actors to idle in a fixed order. On threads,
  `awaitIdle()` waits on the runtime's count of messages not yet handled; it
  never polls.
- **Kept remote-ready.** An `ActorRef` equals by `node / path / incarnation`,
  not by object. `Reply<A>` is a narrow ref, not a closure. `ProtocolTest`
  fails a message type holding a function, a mutable field or a non-data
  class. A delivery is at most once, and order holds per sender and receiver
  only; the docs promise no more.

## Why this shape

A step and not a loop: a loop that calls `receive()` keeps its stack while it
waits, so a million idle actors are a million parked stacks. A step keeps only
its state, which is what lets an idle actor cost what Pekko's does. It is also
what lets `.test()` run the same step as the runtime, with no test-only path.
The alternative, Elm-style, returns effects as data; it is purer, but it makes
"call the repository, then decide" awkward, and blocking is the point here.
Recommended: the context.

## Stack

- [x] **`spec-0059-module`** ([#108](https://github.com/matthewjones372/lark/pull/108)) — `lark-actor`, `Behaviour`, `Next`, `Reply`,
      `.test()` for one actor, `ProtocolTest`, `NoOtherDependenciesTest`.
      Done when: the `Shop` above passes its tests with no thread started.
- [x] **`spec-0059-runtime`** ([#109](https://github.com/matthewjones372/lark/pull/109)) — mailbox, activation, `spawn`, `tell`, `ask`.
      Done when: a step reads `isVirtual`, an idle actor holds no thread, and
      closing the flock stops every actor it spawned.
- [x] **`spec-0059-idle`** ([#110](https://github.com/matthewjones372/lark/pull/110)) — the test scope for many actors, and `awaitIdle()`.
      Done when: the same scenarios pass under both runtimes, with no
      `eventually`, sleep or timed expectation (a detekt rule says so).
- [ ] **`spec-0059-bench`** — `lark-actor-benchmarks`, JMH against Pekko:
      tell 1→1 and N→1, ping-pong p50/p99, heap per idle actor, a 1 ms blocking
      step. Laid out as `lark-stream-benchmarks` is. Done when: a baseline is
      committed and the README quotes it, gaps included.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-actor-benchmarks:jmh
```

## Open questions

Nothing: bounded mailboxes, a readable `state`, `throughput` and one spec are
settled in Shape and Stack.
