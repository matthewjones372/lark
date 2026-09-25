# 0058 — An actor without an actor system

## Problem

An actor is lark's best tool for one writer over some state: the petshop's
`Shop` (`Adoptions.kt`) exists so two adoptions of one tortoise cannot both
succeed. Today that means Pekko. A step must not block, so a repository call
becomes `pipeToSelf`, an extra state and a stash. The test needs an
`ActorSystem`, probes and timed `expectMessage`. And a failure crosses into
`Throwable`, beside lark's declared errors. 0057 smooths the Kotlin surface but
cannot change any of that, because it is Pekko's runtime.

## Not doing

- **Lifecycle, time, topology:** signals, supervision, `watch`, children (0059);
  timers, receive timeout, stash (0060); routers, receptionist, dead letters and
  lark-app nodes (0061).
- **Keyed entities, persistence, transport, membership and sharding** (0062–0065).
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
  `Unhandled`. A throw stops the actor for now; 0059 adds declared failures and
  supervision.
- **The runtime.** `spawn` holds the actor in its `Flock`, so it cannot outlive
  the scope. The mailbox is a lock-free multi-producer queue. An idle actor
  holds no thread. A message to an idle actor starts a virtual thread that runs
  up to `throughput` messages, then parks or yields. The activation is one
  compare-and-set state, as in 0037.
- **Replies and asks.** `ask` waits on the calling thread and answers
  `Either<AskFailure, A>`. `AskFailure` is `TimedOut`, `Stopped` or
  `Unreachable`. The last never happens locally, but adding it later would
  break every `when`. `Reply<A>` sends once, and `A : Any`.
- **Tests.** `.test()` runs the same behaviour on the calling thread. `send`
  and `ask` return once the message and everything it caused are handled.
  `told(ref)` lists what was sent, and a missing or doubled reply fails at
  once. A test scope runs several actors to idle in a fixed order. On threads,
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

- [ ] **`spec-0058-module`** — `lark-actor`, `Behaviour`, `Next`, `Reply`,
      `.test()` for one actor, `ProtocolTest`, `NoOtherDependenciesTest`.
      Done when: the `Shop` above passes its tests with no thread started.
- [ ] **`spec-0058-runtime`** — mailbox, activation, `spawn`, `tell`, `ask`.
      Done when: a step reads `isVirtual`, an idle actor holds no thread, and
      closing the flock stops every actor it spawned.
- [ ] **`spec-0058-idle`** — the test scope for many actors, and `awaitIdle()`.
      Done when: the same scenarios pass under both runtimes, with no
      `eventually`, sleep or timed expectation (a detekt rule says so).
- [ ] **`spec-0058-bench`** — `lark-actor-benchmarks`, JMH against Pekko:
      tell 1→1 and N→1, ping-pong p50/p99, heap per idle actor, a 1 ms blocking
      step. Laid out as `lark-stream-benchmarks` is. Done when: a baseline is
      committed and the README quotes it, gaps included.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-actor-benchmarks:jmh
```

## Open questions

1. **Bounded mailboxes, and deadlock between two full ones?** Recommend
   bounded, with only a `tell` from outside any actor waiting. A `tell` from
   inside an actor takes an overflow policy (fail or dead letter), so a cycle
   cannot deadlock.
2. **May a test read `state`?** Recommend yes: it is an immutable value, not a
   hidden field. The alternative is to assert only through messages.
3. **`throughput` default, and a linger before parking?** Recommend 5, as
   Pekko's, and no linger until the benchmark asks for one.
4. **One spec or two?** This runs past a page. The seam is `.test()` and the
   runtime (entries 1 and 3) against the runtime and the benchmark (2 and 4).
   Recommend keeping them together, since the parity claim needs both.
