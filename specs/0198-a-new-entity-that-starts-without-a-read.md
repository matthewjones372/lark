# 0198 — A new entity that starts without a read

## Problem

A persistent behaviour reads its journal before its first command, every time it starts. For an entity that is
born, does a few steps and is done, such as lark-bank's transfer saga, that read always comes back empty: the
transfer's id was numbered a moment ago. Under load it is one database round trip on every transfer, on the same
pool the appends wait on. lark-bank spec 0031 (`fresh-entity`) wants it gone; today the only workaround is keeping
the saga's state outside the journal, which loses replay.

## Not doing

- **A hint from the caller.** No new `entity(id, fresh = true)` on sharding and no marker on the message. The
  behaviour decides, so nothing changes on the wire.
- **Restarts.** A restart after a failure recovers as today. Only an actor's first start is lazy.
- **Batched behaviours** (`batch > 1`) in the first entry. They follow once the single-step path is proven.
- **Snapshots.** An entity started lazily has none to load yet; one that turns out to exist recovers through the
  ordinary path, snapshot included.

## Shape

```kotlin
persistent<TransferMessage, TransferEvent, Transfer>(
    id = PersistenceId(Kinds.TRANSFER, id),
    empty = Transfer.Unrequested,
    codec = TransferEvents,
    command = { ctx, state, message -> ... },
    event = Transfer::evolve,
    start = Start.assumingNew,   // default Start.recovering, today's behaviour
)
```

With `Start.assumingNew`, the first command is decided against `empty` at sequence 0, with no read:

- **It persists:** the append expects sequence 0. If it succeeds, the journal was empty and the actor carries on,
  having read nothing. If it conflicts, the entity existed: the actor recovers as today, then decides the same
  command again against the recovered state. Nothing is lost and no `then` has run.
- **It persists nothing:** an answer from `empty` could be wrong, so the actor recovers first and decides the
  command against the recovered state. A lazy start costs nothing more than today's.

Start hooks (`onStart`) run after the real state is known: straight away when the first append proves the journal
was empty, or after the recovery otherwise.

## Why this shape

The journal's own conflict check is what makes the guess safe: an append expecting 0 can only succeed on an empty
history, so a wrong guess costs one failed append and the read it would have done anyway. The alternative is a hint
from the caller, which is cheaper to explain but puts a correctness claim in every caller and needs a change to
sharding's envelope. Recommended: the behaviour option.

## Stack

- [ ] **`start-assuming-new`** — `Start.assumingNew` on `persistent`, single-step only.
      Done when: a new entity's first persisting command makes no `read` (a counting journal shows 0), an existing
      one decided lazily ends in the same state and answer as with `Start.recovering`, and a command that persists
      nothing on a lazy start answers from the recovered state.
- [ ] **`start-assuming-new-batched`** — the same for `batch > 1`.
      Done when: the batched tests above pass with `batch = 8`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `onStart` wait for the real state?** Recommend yes, as Shape says: a hook that starts timers or nudges
   from the state must not see `empty` for an entity that exists. The cost is that a lazy actor's hooks run inside
   its first step rather than before it.
2. **Is a conflict on the first append a failure, or a recovery?** Recommend a recovery, with no failure signalled
   and no supervision involved: it is the expected outcome of a wrong guess, not a fault.
3. **Should restarts be lazy too?** Recommend no: a restart follows a failure, so the entity almost certainly exists.
4. **Name.** `Start.assumingNew`, or a plain `assumeNew: Boolean = false`? Recommend the type, which leaves room for
   another kind of start later.
