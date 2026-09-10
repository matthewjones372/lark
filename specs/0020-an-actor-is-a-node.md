# 0020 — An actor is a node

## Problem

A service on Pekko spawns its actors in `main`, in an order it keeps in its
head, and stops them by not stopping them. The graph knows a repository is
needed before a consumer; it does not know the consumer is an actor, so the
actor is spawned outside the graph and given the repository by hand.

Spec 0016 left this out on purpose, and named it a spec of its own.

## Not doing

- **No supervision.** A `Behavior` says what happens when it fails; a node does
  not get an opinion about it.
- **No cluster, sharding or persistence.** An entity is not a node: there are
  as many of it as there are ids.
- **No typed `ActorSystem` as the node.** The system is the application's, and
  `Adapter.spawn` takes the classic one both flavours implement.

## Shape

A module, `lark-app-pekko`, on `lark-app`, `lark-pekko` and `pekko-actor-typed`.

```kotlin
val actors =
    single<ActorSystem> { install({ ActorSystem.create("app") }) { s, _ -> s.terminate() } } +
    actor<IngestCommand>("ingest") { repo: UserRepo -> IngestBehavior.create(repo) } +
    actor<ReportCommand>("report") { repo: UserRepo, orders: OrderRepo -> ReportBehavior.create(repo, orders) }
```

A node depending on one names the protocol it sends:

```kotlin
single { ingest: ActorRef<IngestCommand>, http: HttpConfig -> HttpServer(http, ingest) }
```

Readiness is the probe that is already there:

```kotlin
actor<IngestCommand>("ingest") { repo: UserRepo -> IngestBehavior.create(repo) }
    .probe("ingest", timeout = 3.seconds, attempts = 10, interval = 200.milliseconds) { ref ->
        AskPattern.ask(ref, ::Ping, ofSeconds(3), scheduler).await() is Pong
    }
```

## Why this shape

Keyed by `ActorRef<T>` rather than by name. Two actors answering one protocol
are one key and want a router or a pool, which is a behaviour rather than two
nodes; two protocols are two keys with no ceremony. It also means a dependent
declares what it sends rather than a string.

The stop is awaited through `gracefulStop`, so an actor holding a connection
has given it back before the node that opened the connection is released.
Reverse-topological release already puts the actor first; awaiting is what makes
that ordering mean anything.

## Stack

- [x] **`spec-0020-actor`** ([#28](https://github.com/matthewjones372/lark/pull/28)) — the module, `spawn`, `actor` for nought to two
      dependencies, and the dependency test.
      Done when: two protocols are two nodes, a dependent is handed the ref, and
      an actor has stopped before the graph returns.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `lark-app-pekko` accept `pekko-slf4j` and `slf4j-api` arriving with
   `pekko-actor-typed`?** Recommend yes, and say so in the dependency test:
   typed Pekko logs through slf4j rather than the classic event bus, so
   excluding the bridge trades a shorter classpath for a logger that may not
   work.
2. **Should the classic `ActorSystem` be a node this module provides?**
   Recommend not: naming it, configuring it and deciding its shutdown belong to
   the application, and `single<ActorSystem> { … }` is one line.
