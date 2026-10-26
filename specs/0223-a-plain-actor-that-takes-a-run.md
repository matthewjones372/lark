# 0223 — A plain actor that takes a run

Merged as 0198 (#605), eleven minutes after another spec had taken that number; renumbered to 0223. Its branches and
the commits that built it say 0198.

## Problem

Spec 0086 gave `Behaviour` a plural step and kept it internal, with `persistent(…, batch = n)` its only user. An actor
that writes somewhere other than the journal cannot use it. Petshop's shop actor makes two synchronous Postgres
writes per adoption, and probes show they are 91% of its time at 250 adoptions a second, capping it near 400 against
a target of 1,000. The messages are waiting in the mailbox; they are written one transaction at a time. The way out
today is `persistent` (an event store petshop does not want) or giving up the single writer.

Second, a run runs inside its first message's carried context (spec 0122). The shop writes one outbox row per
message, and each row records the trace of the request that sent that message; a run of 64 writes 63 rows with the
wrong trace.

## Not doing

- **No change to `persistent`.** It keeps deciding its batch inside the first command's context, as 0122 says.
- **No per-message `Next`.** A run answers one `Next` for the whole run; replies are the step's own business.
- **No change to `Carrier`.** It gets no "clear" operation; see Why this shape.
- **No holding back.** A run is what is already waiting, as in 0086; a lone message is never kept for a second.

## Shape

```kotlin
/** A run of messages handled by one step, each with what its sender carried. */
interface Run<out M : Any> : List<M> {
    /** Runs [block] inside what the message at [index] carried from its sender, and nothing else's. */
    fun <A> within(index: Int, block: () -> A): A
}

fun <M : Any, S> batching(
    initial: S,
    batch: Int,
    steps: Fail<Nothing>.(ctx: Ctx<M>, state: S, messages: Run<M>) -> Next<S>,
): Behaviour<M, S, Nothing>

@JvmName("raisingBatching")
fun <M : Any, S, E> batching(initial: S, batch: Int, steps: Fail<E>.(Ctx<M>, S, Run<M>) -> Next<S>): Behaviour<M, S, E>

val shop = batching<ShopCommand, Shop>(Shop.empty, batch = 64) { ctx, shop, commands ->
    val decided = commands.decideAll(shop)                       // in memory, in order
    db.transaction { commands.indices.forEach { i -> commands.within(i) { writeOutbox(decided[i]) } } }
    commands.indices.forEach { i -> commands.within(i) { decided[i].reply() } }
    become(decided.last().state)
}.onStart { ctx -> … }
```

- A run is the plain messages waiting, up to `batch`, as 0086 hands `steps` them. A message handled alone — a timer's,
  a stashed replay, a listing, one behind a signal at the end of a turn, or any when `batch = 1` — goes through the
  same `steps` as a run of one. Signals still go to `onSignal`, and `onStart` works as on any behaviour.
- `within(i)` binds exactly message `i`'s carried map. Outside `within`, a run of more than one binds no message's
  context; a run of one is in its message's, as a single step is.
- `unhandled()` sends every message of the run to dead letters; `stop()` stops after the run. A raise or a throw
  fails the whole run, as 0086 decided for `persistent`.
- `ctx.stash(m)` inside `within(i)` keeps `m` with message `i`'s context, since stash captures what is bound.

## Why this shape

A `Run` that *is* the `List<M>` keeps the common case — read the messages — free of wrappers, and adds one method for
the context. The alternative, `List<Waiting<M>>` with a `message` and a `within` each, makes every step unwrap, and
is not recommended. The step runs outside any message's context rather than the first's because carriers bind but
never clear: inside the first's, `within(i)` for a message that carried nothing would still see the first's trace.
Running unbound makes `within(i)` exact. `persistent` gets the old behaviour by wrapping its batch in `within(0)`.

## Stack

- [x] **`spec-0196-batching`** (#605) — `Run`, `batching`, the runtime handing `steps` a `Run`, and `persistent` wrapping
      in `within(0)`; the guide's paragraph.
      Done when: `BatchingTest` shows a run sees `[1, 2, 3]` told before start, each `within(i)` sees message `i`'s
      annotation and one that carried nothing sees none, a lone message reaches `steps` as a run of one in its own
      context, and `onStart`/`onSignal` run; `PersistentBatchTest` and `CarriedTest` unchanged.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Name.** `batching` beside `behaviour`, or `behaviour(initial, batch = n) { … }` as an overload? Recommend
   `batching`: the step's third argument changes type, and an overload that differs only there resolves badly. **Answered:** as recommended.
2. **Index or message in `within`?** Recommend the index: messages may be equal, and a data object is one value. **Answered:** as recommended.
3. **Context outside `within` for a run.** None (recommended, for the reason above), or the first's as `persistent`? **Answered:** as recommended.
