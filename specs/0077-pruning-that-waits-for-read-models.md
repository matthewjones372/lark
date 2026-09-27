# 0077 — Pruning that waits for read models

## Problem

0076 deletes an entity's events once two snapshots cover them, and says
plainly that a read model (0075) which has not read an event yet will never
see it. Today a service that projects a kind it also prunes has to keep every
projection ahead of every snapshot by hand, or not prune at all. A projection
that falls behind for any reason, such as a deploy, a slow database or a
restart, loses events without a word, and the read model is wrong from then
on.

## Not doing

- **Discovering read models.** A behaviour names the read models it waits
  for. lark does not keep a registry of every projection a service runs.
- **Waiting for a read model that has never run.** A name with no saved offset
  holds pruning back entirely, which is the safe answer. Deleting a read
  model's offset is how a service stops waiting for it.
- **Pruning on a schedule.** Deletion still happens only when a snapshot is
  saved; a read model that catches up lets the next snapshot delete what the
  last one could not.

## Shape

```kotlin
snapshots = every(100, Order.codec, prune = Prune.after(offsets, "order-totals", "order-search"))
// or Prune.always (0076's prune = true), or Prune.never (the default)
```

- **`JournalPruning.deleteTo(id, sequence, readTo)`.** It deletes only
  events whose feed offset is at most `readTo`, as well as at most
  `sequence`. An id's offsets grow with its sequence numbers, so what goes is
  still a prefix, and the newest event is still kept.
  `InMemoryJournal` and `JdbcJournal` implement it.
- **`Prune.after(offsets, names…)`.** When a snapshot is saved, the step reads
  each named read model's offset from `offsets` and deletes only what all of
  them have passed, the smallest of them as `readTo`. A name with no offset
  saved holds back everything.
- **`Prune.always`** is 0076's behaviour, `readTo` unbounded; `prune = true`
  becomes `prune = Prune.always`.

## Why this shape

The read models' offsets already exist, in the store `runProjecting` saves
them to, so waiting for them needs no new bookkeeping: one read per name,
once per snapshot. Naming the read models on the behaviour keeps the rule
next to the pruning it limits. The alternative is a registry of projections
that every pruning consults. It is more automatic, and it is one more thing
to keep consistent across nodes. Recommended: named on the behaviour.

## Stack

- [ ] **`spec-0077-bound`** — `readTo` on `JournalPruning`, in memory and on
      JDBC, and in `PruneContract`. Done when: a deletion bounded by a feed
      offset keeps every event after it, on both journals.
- [ ] **`spec-0077-prune`** — `Prune.never`, `Prune.always` and
      `Prune.after`. Done when: a read model behind the snapshots holds
      pruning back to its offset, a read model with no offset holds it back
      entirely, and once it catches up the next snapshot deletes the rest.
- [ ] **`spec-0077-follow`** — the two together. Done when: in
      `lark-actor-projection`, a projection following a kind that prunes
      after it handles every one of 1,050 events.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Named on the behaviour, or a registry of projections?** Recommended:
  named on the behaviour, as above.
- **A read model with no saved offset: hold back everything, or ignore it?**
  Recommended: hold back everything; ignoring it deletes what it has not read.
- **Replace `prune: Boolean` with `Prune`, since 0076 is unreleased?**
  Recommended: yes, rather than carrying both.
