# 0198 — A new entity that starts without a read

## Problem

A persistent behaviour reads its journal before its first command, every time it starts. For an entity that is
born, does a few steps and is done, such as surefold-bank's transfer saga, that read always comes back empty: the
transfer's id was numbered a moment ago. Under load it is one database round trip on every transfer, on the same
pool the appends wait on. surefold-bank spec 0031 (`fresh-entity`) wants it gone; today the only workaround is keeping
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

With `Start.assumingNew`, an actor's first start reads nothing: it starts at `empty`, sequence 0, and its start
hooks (`onStart`) run with that state, as they do today for an entity whose journal is empty. Its first command:

- **Persists:** the append expects sequence 0. If it succeeds, the journal was empty and the guess was right. If it
  conflicts, the entity existed.
- **Persists nothing:** an answer from `empty` could be wrong, so the guess is treated as wrong.

A wrong guess restarts the actor as a failure under supervision would, without asking supervision: timers,
registrations and the stash go, it recovers by reading its journal as today, its start hooks run again with the
recovered state, and then the same command is decided against that state. No `then` ran for the guess. Restarts
after that, and every restart after a failure, recover as today.

## Why this shape

The journal's own conflict check is what makes the guess safe: an append expecting 0 can only succeed on an empty
history, so a wrong guess costs one failed append and the read it would have done anyway. The alternative is a hint
from the caller, which is cheaper to explain but puts a correctness claim in every caller and needs a change to
sharding's envelope. Recommended: the behaviour option.

## Stack

- [x] **`start-assuming-new`** ([#624](https://github.com/matthewjones372/surefold/pull/624)) — `Start.assumingNew` on `persistent`, single-step only, and the restart a wrong
      guess makes, in both the flock's runtime and `TestActors`.
      Done when: a new entity's first persisting command makes no `read` (a counting journal shows 0), an existing
      one decided lazily ends in the same state and answer as with `Start.recovering`, and a command that persists
      nothing on a lazy start answers from the recovered state.
- [ ] **`start-assuming-new-batched`** — the same for `batch > 1`.
      Done when: the batched tests above pass with `batch = 8`.
      `AssumingNewSuite` runs the first entry's tests at `batch = 1` and `batch = 8`. Three more on threads hold the
      start until the commands are waiting, so the first turn is a real run: one append and no read for a new entity;
      a conflict that recovers and decides the run again, in order; a run that writes nothing decided again against
      what was recovered. A guessed run that writes nothing or conflicts throws before any `then`, and the runtime
      hands every message of the run back through the stash, sized for the whole run. `TestActors` gives each message
      its own turn, so its single-step handling already covers it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered (maintainer, 2026-10-27: go with the recommendations).

1. ~~Should `onStart` wait for the real state?~~ Answered by building (2026-10-25): a hook is wrapped around the
   start from outside (`onStart`, or surefold-bank's own `onRecovered`), so `persistent` cannot hold it back until its
   first command without changing how `Behaviour` composes a start. Instead hooks run with `empty`, and a wrong guess
   restarts the actor so they run again with the recovered state. What a hook did with `empty` is undone by the
   restart, except a message it sent elsewhere.
2. **Is a conflict on the first append a failure, or a recovery?** Recommend a restart that supervision is not asked
   about and that counts as no failure: it is the expected outcome of a wrong guess, not a fault.
3. **Should restarts be lazy too?** Recommend no: a restart follows a failure, so the entity almost certainly exists.
4. **Name.** `Start.assumingNew`, or a plain `assumeNew: Boolean = false`? Recommend the type, which leaves room for
   another kind of start later.
