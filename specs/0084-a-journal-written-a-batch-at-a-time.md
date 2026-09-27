# 0084 — A journal written a batch at a time

## Problem

`persistent` appends once per command: a step decides, `ctx.journal.append` writes that command's events and
returns, and only then does the next message get a step. Each append is a round trip and a commit, so a single
persistent actor handles at most one command per journal round trip. On a local Postgres that is about
1–2 ms, or 500–1,000 commands a second, however idle the machine is.

lark-bank hits this on its `hot` scenario: every payment goes to one merchant account, and the account is one
actor, so it has one writer by design. Sharding cannot help, because it is one id. The alternatives today are to
split the account into sub-accounts in application code, or to give up the single writer. Both abandon what
`persistent` is for.

A cell already drains up to `throughput` (64) messages per activation. The messages are waiting; they are just
written one at a time.

## Not doing

- **No async journal.** `append` stays blocking, on the step's virtual thread.
- **No change to `persistent` without the option.** A persistent actor that does not ask for batches behaves
  exactly as it does today.
- **No cross-actor batching.** Batching appends from many actors into one statement is the journal's business,
  and `JdbcJournal` can grow it later on its own.
- **No reordering.** Commands are decided in mailbox order, each against the state the one before it left.

## Shape

```kotlin
fun account(id: String) = persistent<AccountCommand, AccountEvent, Account>(
    id = PersistenceId("account", id),
    empty = Account.Unopened,
    codec = AccountEvents,
    command = { _, state, command -> /* unchanged */ },
    event = Account::evolve,
    batch = 64,                    // decide up to 64 waiting commands, then one append for all of them
)
```

- With `batch = n`, the cell hands the behaviour up to `n` waiting messages at once. Each command is decided in
  turn against the state the previous one left, which is applied in memory. Their events, and any delivery marks
  (spec 0079), go in **one** `append`. After it returns, each command's `then` runs in order, with the state as
  that command left it.
- A batch holds only the messages already in the mailbox. A lone command is never held back to wait for a
  second.
- A command that answers `none()` or is dropped as a duplicate adds nothing to the append, but its `then` still
  runs in order. One that answers `unhandled()` or `stop()` ends the batch there, and the commands after it wait
  for the next activation.
- A snapshot is taken if the batch's append crossed a multiple of `every`.
- An append that conflicts raises `JournalConflict` for the whole batch, and no `then` of that batch runs.
  Supervision recovers as today, and the mailbox is kept, so every command is decided again against the
  recovered state.

The seam underneath is one addition to `Behaviour`: an optional
`steps: (Raise<E>.(ctx, state, messages: List<M>) -> Next<S>)?`, with `drain` passing a run of plain messages to it
when it is set. Signals, timers and stashed replays still go one at a time.

## Why this shape

Batching in the behaviour needs the runtime to show it the queue, which is why `Behaviour` grows a plural step.
The runtime already polls in batches, so the change is small. The alternative is a journal that coalesces
concurrent appends — group commit inside `JdbcJournal`. That does nothing here: one actor never has two appends
in flight, so there is nothing to coalesce. The recommendation is the plural step, with `persistent` as its only
user until something else needs it.

## Stack

- [ ] **`spec-0084-steps`** — `Behaviour.steps`, and `drain` handing it a run of plain messages up to the
      behaviour's batch size.
      Done when: a test behaviour with `steps` sees `[1, 2, 3]` for three messages told before it started, and a
      signal between them splits the run.
- [ ] **`spec-0084-persistent`** — `persistent(batch = n)`: decide in turn, one append, `then` in order,
      snapshots and delivery marks across the batch.
      Done when: 1,000 commands told at once to a `batch = 64` actor on `JdbcJournal` make ≤ 20 appends; its state
      and journal equal the unbatched actor's; a conflict mid-run recovers to the same final state.
- [ ] **`spec-0084-numbers`** — the benchmark in `lark-actor-benchmarks` and its README row.
      Done when: one hot persistent actor on embedded Postgres is measured at batch 1 and 64.

## Acceptance

```bash
./gradlew build
./gradlew :lark-actor-journal-jdbc:test --tests '*Batch*'
```

## Open questions

1. **Should `batch` default to 1 or to `throughput`?** Recommend 1: batching changes when a `then` runs relative
   to the write of later commands, and that is a behaviour change a caller should opt into.
2. **Should a raise from one command fail the whole batch?** Recommend yes, as a thrown exception does today.
   The alternative is to write the commands before it and re-queue the rest, which is subtle for little gain.
3. **Should `steps` be public API or internal to `persistent`?** Recommend internal first. A behaviour that
   batches by hand is rare, and the seam is easier to widen later than to narrow.
