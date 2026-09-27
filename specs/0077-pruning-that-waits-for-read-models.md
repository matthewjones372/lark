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

- [x] **`spec-0077-bound`** — `readTo` on `JournalPruning`, in memory and on
      JDBC, and in `PruneContract`. Done when: a deletion bounded by a feed
      offset keeps every event after it, on both journals.
      ([#199](https://github.com/matthewjones372/lark/pull/199))
- [x] **`spec-0077-prune`** — `Prune.never`, `Prune.always` and
      `Prune.after`. Done when: a read model behind the snapshots holds
      pruning back to its offset, a read model with no offset holds it back
      entirely, and once it catches up the next snapshot deletes the rest.
      ([#200](https://github.com/matthewjones372/lark/pull/200))
- [x] **`spec-0077-follow`** — the two together. Done when: in
      `lark-actor-projection`, a projection following a kind that prunes
      after it handles every one of 1,050 events.
      ([#201](https://github.com/matthewjones372/lark/pull/201))

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

Decided (2026-09-27): every open question goes as recommended. A behaviour
names the read models its pruning waits for; a read model with no saved
offset holds back everything; and `Prune` replaces `prune: Boolean`.

Decided while building `spec-0077-bound`: `readTo` is a parameter of
`deleteTo` with no bound as its default, so 0076's callers read the same. A
journal turns it into the last sequence number of the id at or before that
offset, and deletes up to the smallest of that, the sequence asked for, and
one before the newest.

Decided while building `spec-0077-prune`: `Prune` is a `fun interface` that
answers the feed offset pruning may reach now, or null for none, so a service
can write its own rule; `never`, `always` and `after` are on its companion.
`after` reads its offsets when a snapshot is saved, inside the same logged
block as the deletion, so an offset store that throws deletes nothing and
fails no step. A name with no offset counts as offset 0.

Decided while building `spec-0077-follow`: the test writes all 1,050 events
before the projection starts, the case pruning without waiting gets wrong: with
`Prune.always` the late projection finds 150 events, and with `Prune.after` it
finds all of them, then lets the next snapshot delete what it has passed.
