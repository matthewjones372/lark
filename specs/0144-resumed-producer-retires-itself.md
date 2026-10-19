# 0144: a resumed producer retires itself

## Problem

The cluster's `lark-producers` singleton retires a resumed producer by hand
(`cluster/Producers.kt:169-193`). It polls `drain(Duration.ZERO)` on every check,
stops the child itself, and prunes the producer's journal through
`(ctx.journal as? JournalPruning)?.deleteTo(PersistenceId("lark-producer", key), …)`.
So the cluster knows the durable producer's persistence id, its drained test
and its storage, and `Ctx.resumedProducer` returns `Pair<Producer, ActorRef<*>>`
only so the caller can stop it. The `"lark-producer"` literal appears once in
`DurableProducer.kt:158`, once in the cluster, and in nine test assertions
across two modules.

## Not doing

- Changing what a resumed producer sends, when it resends, or its restart
  schedule.
- Changing `Flock.durableProducer` or `Sharded.reliable`'s interface.
- Requiring every journal to be a `JournalPruning`.
- Fixing the registry forgetting its running children on restart
  (`Resuming.started`); that is a separate question.

## Shape

```kotlin
// lark-actor
fun <M : Any> Ctx<*>.resumedProducer(
    id: String,
    codec: EventCodec<M>,
    resendAfter: Duration,
    retired: () -> Unit,               // called once, after it stops and prunes
    route: (to: String) -> ActorRef<M>,
): Producer<M>

// lark-cluster, Resuming.next
running += producer
kinds.getValue(listed.kind).resume(ctx, producer) { ctx.self.tell(Registry.Retired(producer)) }
```

The outbox actor of a resumed producer checks, after recovery and after each
`Confirmed`, whether every outbox is empty. When it is, it deletes its own
events (where the journal prunes), stops, and calls `retired`. The registry
writes `Change.Retired` when `Registry.Retired` arrives, not from a poll.
`Resuming.running` becomes a set of ids.

Tests read a producer's events through `PersistenceId.producer(id)`, so the
literal lives in `DurableProducer.kt` alone.

## Why this shape

Retirement is a fact about the producer, so the producer owns it: the drained
test, the prune and the stop sit next to the outbox that knows them. `retired`
is a plain callback rather than an `ActorRef<R>` plus a message, so
`resumedProducer` needs no extra type parameter; the caller makes it a `tell`,
which is safe from any thread. Watching the child for `Terminated` instead was
weighed and rejected: a crash ends in `Terminated` too, and must not look like
a clean retirement.

## Stack

- [ ] **`spec-0144-retire`**: `resumedProducer` takes `retired`, retires
      itself, returns `Producer<M>`; `Resuming` listens for
      `Registry.Retired` and drops its drain poll and prune.
      Done when: `ProducersTest` passes unchanged in what it asserts, and
      `cluster/Producers.kt` names neither `JournalPruning` nor
      `"lark-producer"`.
- [ ] **`spec-0144-producer-id`**: `PersistenceId.producer(id)`; the nine
      test sites use it.
      Done when: `grep -rn '"lark-producer"'` finds only `DurableProducer.kt`.

## Acceptance

```bash
./gradlew :lark-actor:test :lark-cluster:test
grep -rn '"lark-producer"' --include='*.kt' .   # DurableProducer.kt only
```

## Open questions

1. Should `PersistenceId.producer(id)` be public, or `@TestOnly` in
   `lark-test`? Recommend public: an operator inspecting a JDBC journal needs
   the same id, and it costs one line.
2. A resumed producer that recovers nothing: does it retire at once, before
   the registry has listed it as running? Recommend yes, and the registry
   treats a `Retired` for an id it never ran as still worth writing.
3. Should a journal that cannot prune log at debug once (agreed in chat), or
   stay silent as today? Recommend debug, logged by the outbox actor at
   retirement.
