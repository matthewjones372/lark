# 0057 — An actor written in Kotlin

## Problem

0020 made an actor a node and gave the caller `ask`. Writing the actor itself
is still Pekko's Java DSL. `AskTest` spells it with class tokens:
`Behaviors.receive(Ledger::class.java).onMessage(Look::class.java) { …;
Behaviors.same() }.build()`. The petshop's `Adoptions.kt` uses
`receiveMessage` and a `when`, which is the right shape. It still needs a
hand-written `answered` helper to reply and return `same()` together, and that
helper is typed to one protocol.

An actor that has to call something blocking, such as a repository, has no lark
answer. The work belongs on a virtual thread and the result should come back to
the actor as a message. The caller writes that bridge by hand today: a
`CompletableFuture`, then `context.pipeToSelf`, then a
`(value, throwable) -> Message` mapping. That mapping folds a declared failure
and a defect into one `Throwable`, which is the second error model AGENTS.md
rules out.

## Not doing

- **No new actor model.** A behaviour is still Pekko's `Behavior<T>`, spawned,
  supervised and stopped by Pekko. Every helper returns one.
- **No classic actors, persistence, cluster or sharding.**
- **No test kit.** Pekko's `BehaviorTestKit` already runs a behaviour
  synchronously, without a system.
- **No backend-free actor description** (see Why this shape).
- **No change to `actor<T>(…)` in `lark-app-pekko`** beyond the import of `ask`.

## Shape

A module, `lark-actor-pekko`, on `lark-pekko` and `pekko-actor-typed`. `ask`
moves into it, and `lark-app-pekko` takes it as `api`.

```kotlin
fun shop(state: State): Behavior<Shop> = receive { message ->   // receive<Shop>, reified
    when (message) {                                          // no else: a new message is a compile error
        is Arrived -> shop(state.record(message.pet))
        is Find -> message.replyTo.answer(Option.fromNullable(state.pets[message.id]))
        is Everything -> message.replyTo.answer(state.pets.values.toList())
        is Load -> fork(::Loaded) { repo.load(message.id).bind() }  // Raise<E> on a virtual thread
        is Loaded -> message.outcome.fold({ shop(state.failed(it)) }, { shop(state.with(it)) })
    }
}

data class Loaded(val outcome: Either<RepoError, Pet>) : Shop
```

- `receive<T> { message -> … }` and `receive<T> { context, message -> … }` wrap
  `Behaviors.receiveMessage` and `Behaviors.setup`. `same()` and `stopped()` are
  top-level, so a branch reads without the `Behaviors.` prefix.
- `ActorRef<A>.answer(reply: A): Behavior<T>` sends and stays. `A : Any`, for
  `ask`'s reason: a null reply cannot be sent.
- `fork(wrap) { … }` runs a `Raise<E>` block on `VirtualThreads` or a given
  executor. It sends `wrap(Either<E, A>)` back through `pipeToSelf` and returns
  `same()`. A declared failure arrives as a `Left`. A throw is a defect: the
  actor fails, and its supervision decides what happens next.

## Why this shape

The helpers are thin by design. Pekko's behaviour model is sound, and the
friction is only in the Java-facing DSL. Every helper compiles to the call it
replaces, so a reader who knows Pekko reads the result unchanged. `fork` is the
one piece with semantics, and it keeps 0002's rule: a `Raise` scope never
crosses a thread, so the work answers with an `Either` and the actor handles it
as a message.

The streams-style alternative describes an actor as a backend-free fold,
`(S, M) -> Step<S>`, and interprets it on Pekko or on Forks with 0038's
channels. That makes an actor testable and portable without Pekko, like 0046.
It also means a second actor model beside Pekko's, with supervision, stash and
timers each reimplemented per backend. Recommended against here. If it is
wanted, it should be its own spec after this one.

## Stack

- [ ] **`spec-0057-module`** — `lark-actor-pekko`, `ask` moved into it, and its
      `NoOtherDependenciesTest`.
      Done when: `lark-app-pekko` builds against it unchanged, and the
      dependency test names `lark-pekko` and `pekko-actor-typed` only.
- [ ] **`spec-0057-receive`** — `receive`, `same`, `stopped` and `answer`, with
      `AskTest`'s ledger rewritten in them.
      Done when: a sealed protocol missing a branch fails to compile, and a
      nullable `answer` fails to compile (both via `EmbeddedKotlin`).
- [ ] **`spec-0057-fork`** — `fork` from an actor.
      Done when: the body reads `isVirtual` as true, a `raise` arrives as a
      `Left` message, and a throw fails the actor rather than arriving as a
      message.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **A new module, or these helpers inside `lark-app-pekko`?** Recommend a new
   module. An actor written in Kotlin does not need the wiring graph, and
   `lark-app-pekko` brings `lark-app` with it.
2. **Should `ask` time out as a value?** Today it throws Pekko's
   `TimeoutException`. Recommend adding `Raise<AskTimedOut>.ask` beside it. A
   caller should expect a timeout, but existing callers should not have to
   change.
3. **`fork` where a lark `Flock` is already open?** Recommend `fork` running on
   its own. An actor has no scope to join, and an actor that stops while a
   fork is still running drops the reply, which `pipeToSelf` already does.
4. **Timers, stash and `Behaviors.withTimers`?** Recommend leaving them to
   Pekko until a service asks for them; they read fine from Kotlin already.
