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
cluster.spread("statements", 8) { k ->                  // 8 workers, as even as the members allow, each moving
    Projection.worker {                                  // if its member goes; the worker runs what it starts
        Projection.partitioned(feed, kind, codec, offsets, "statements", partition = k, partitions = 8)
            .groupedWithin(500, 200.milliseconds)
            .map { batch -> write(batch) }
            .runProjecting()
            .start(backend)
    }
}
```

- **Each partition reads its own slices.** The journal's `slice` column (spec 0105), set on append, is indexed
  with `(kind, slice, ordering)`. Partition *k* of *n* reads slices `k × 1024 ÷ n until (k + 1) × 1024 ÷ n`, through
  `SlicedFeed.after(kind, slices, offset, limit)`; `InMemoryJournal` and `JdbcJournal` are both sliced feeds.
- **One watermark per journal.** Filtered by slice, a partition sees every other slice's rows as gaps. So the gap
  and horizon rule of spec 0100 moves into one walk over the `ordering` column alone, of every kind, behind a lock
  in each `JdbcJournal`. It publishes a *watermark*: the highest ordering below which no row can still appear.
  Every feed on the journal, the whole kind's as well as each partition's, reads up to the watermark, never past
  it, and never waits on gaps of its own. The walk keeps what it has settled and goes on from there, at most four
  batches of 10,000 orderings a call, so a node started against a large journal catches up over several calls
  rather than stalling one.
- **Offsets per partition:** `statements#3`, or `statements@db-0#3` with the database's name in it. A worker saves
  its own offset and resumes from it.
- **Placement:** `Cluster.spread(name, count)` starts `count` workers the way a singleton is started: on its owner
  as soon as it is placed, with no message sent, and moved by the singleton's handoff when that member leaves or is
  downed. Each worker goes to the member that scores highest for it among those still short of their share, so the
  workers are as even as they can be and a change of members moves few besides those it must.

## Why this shape

The watermark keeps spec 0100's guarantee, that a slow writer's event is never passed, while reading each row only
once across all partitions. The alternative, every partition reading the whole feed and dropping other slices'
rows, keeps the gap logic unchanged but multiplies reads by the partition count. Workers placed like singletons,
but spread, reuse the handoff that keeps one running at a time, where *n* named singletons would pile up on the
oldest node, and entities never passivated would need a message to start and again after every move.

## Stack

- [x] **`spec-0106-slice-column`** — `slice` on append and its backfill came with spec 0105; this adds the
      `(kind, slice, ordering)` index.
- [x] **`spec-0106-watermark`** — the per-journal watermark, and the existing feed rebuilt on it.
      Done when: every feed test from spec 0100 passes against the watermark, on H2 and Postgres, and partitions
      by slice read every event once between them, none past a gap in another's slices.
- [x] **`spec-0106-partitioned`** — `Projection.partitioned`, per-partition offsets, `Projection.worker`, and
      `Cluster.spread`.
      Done when: 8 partitions over 3 nodes project 1,000,000 events, each at least once and each id's in order;
      with a node leaving halfway, its partitions resume elsewhere from their offsets, and only a moved partition
      sees an event twice. Met in about 10 seconds with none seen twice (`SpreadProjectionTest`), over an in-memory
      journal; the JDBC watermark is tested on its own.

## Acceptance

```bash
./gradlew :lark-actor:check :lark-actor-journal-jdbc:check :lark-actor-projection:check :lark-cluster:check
```

## Settled

1. **A way to keep an entity from being passivated, or a new placement primitive?** A placement primitive, and a
   small one: `Cluster.spread`, the eager placement singletons already use, with *n* workers and a balanced owner.
   An entity never passivated would still need a message to start, and another after every move.
2. **Should the watermark reader be a singleton per database?** No: one per journal, on each node, shared by every
   feed there. Any node's watermark is safe on its own, since it only ever passes what the gap rule passes; a
   singleton would add a hop and a way to fail, to save a query over orderings alone.
3. **Filling `slice` on a large existing journal:** not needed: no journal predates the column (spec 0107), and every
   row has one.
