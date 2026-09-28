# 0106 — A projection across nodes

## Problem

A projection follows one database's feed and runs in one place, usually a cluster singleton. Its throughput is one
writer's: one query, one batch and one transaction at a time. However many nodes the cluster has, a read model
falls behind once the events it follows outpace that writer. lark-bank's statements, ledger and transfer status
each run this way, one per journal database, so read freshness cannot grow with the cluster. Pekko Projections
splits a projection by slice range across the cluster for the same reason.

## Not doing

- **Ordering across partitions.** Each partition keeps every id's events in order, as the feed does today. Two
  ids in different partitions have no order between them, which is already true across databases (spec 0088).
- **Changing a projection's partition count while it runs.** Changing it is a restart that re-reads from the
  lowest offset it overlaps, so projections must be idempotent by `(id, sequence)`.

## Shape

```kotlin
Projection.partitioned(feed, kind, codec, offsets, name = "statements", partitions = 8)
    .groupedWithin(500, 200.milliseconds)
    .map { batch -> write(batch) }
    .runProjecting()
    .startAcross(cluster)          // 8 workers, spread over the members, each moving if its node goes
```

- **Each partition reads its own slices.** The journal gains a `slice` column, set on append from the id (spec
  0088's hash) and indexed with `(kind, slice, ordering)`. Partition *k* of *n* reads slices
  `k × 1024 ÷ n until (k + 1) × 1024 ÷ n`.
- **One watermark per database.** Filtered by slice, a partition sees every other slice's rows as gaps. So the
  gap and horizon rule of spec 0100 moves into one reader per database. It follows only the `ordering` column and
  publishes a *watermark*: the highest ordering below which no row can still appear. Every partition reads its
  slices up to the watermark, never past it, and never waits on gaps of its own.
- **Offsets per partition:** `statements@db-0#3`. A worker saves its own offset and resumes from it.
- **Placement:** the workers run as sharded entities that are never passivated, named `name#k`. Sharding spreads
  them over the members and moves them when a member leaves or is downed.

## Why this shape

The watermark keeps spec 0100's guarantee, that a slow writer's event is never passed, while reading each row only
once across all partitions. The alternative, every partition reading the whole feed and dropping other slices'
rows, keeps the gap logic unchanged but multiplies reads by the partition count. Workers as sharded entities reuse
placement, rebalancing and handover, where the alternative, *n* named singletons, would pile up on the oldest node.

## Stack

- [ ] **`spec-0106-slice-column`** — `slice` on append, its index, and a migration that fills it for existing rows.
- [ ] **`spec-0106-watermark`** — the per-database watermark reader, and the existing feed rebuilt on it.
      Done when: every feed test from spec 0100 passes against the watermark.
- [ ] **`spec-0106-partitioned`** — `Projection.partitioned`, per-partition offsets, and workers placed by sharding.
      Done when: 8 partitions over 3 nodes project 1,000,000 events exactly once each; with a node killed
      halfway, its partitions resume elsewhere from their offsets.

## Acceptance

```bash
./gradlew :lark-actor-journal-jdbc:check :lark-actor-projection:check :lark-cluster:check
```

## Open questions

1. **A way to keep an entity from being passivated, or a new placement primitive for long-running workers?**
   Recommended: a sharding option, `passivateAfter = null`. It is the smallest change, and it is useful elsewhere.
2. **Should the watermark reader be a singleton per database?** Recommended: yes. It is cheap (orderings only),
   and one reader keeps one view of the horizon.
3. **Filling `slice` on a large existing journal:** online in batches, or offline? Recommended: online, in
   batches by ordering, with the partitioned feed refusing to start until it is complete.
