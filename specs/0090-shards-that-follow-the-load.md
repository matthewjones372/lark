# 0090 — Shards that follow the load

## Problem

Since 0070 a shard's owner is the `Up` member that scores highest for it by
rendezvous hash. Shards spread evenly by count, but not by work. A kind whose
ids are skewed, such as one customer with most of the traffic, or a node that
is slower than the rest, leaves some members with far more running entities
and far more messages than others. 0081's `lark.sharding.entities` gauge shows
it. The only lever a service has is to add nodes, which moves shards at random
with respect to load, and the busy shard may land on the new node's busy
neighbour anyway.

## Not doing

- **Moving single entities.** The unit that moves is a shard, as every other
  move is. Splitting a hot shard is a different spec.
- **A coordinator that owns placement.** Placement stays computed on every
  node from what they already agree on, plus what this spec adds to the
  gossip.
- **Load measured by CPU or latency.** A node's load is what lark can count
  without sampling: running entities and messages handled per shard.
- **Automatic moves by default.** Rebalancing is asked for per kind; a kind
  that does not ask places by hash alone, as now.

## Shape

```kotlin
val orders = cluster.sharding("order", OrderCodec, passivateAfter = 2.minutes,
    rebalance = Rebalance.byLoad(every = 1.minutes, tolerance = 0.2, mostMoves = 4)) { id -> order(id) }
```

- **Load is gossiped.** Each member adds, per kind that rebalances, the number
  of entities it runs and messages it handled per shard over the last
  interval, to its gossip entry. This is a few bytes per shard it owns.
- **The leader proposes moves.** Every `every`, the leader compares members'
  load. When one exceeds the mean by more than `tolerance`, it moves up to
  `mostMoves` of its busiest shards to the least-loaded members, as overrides
  of the hash placement written into the gossip, with a version.
- **Every node applies them alike.** A shard's owner is its override if one is
  set and that member is `Up`, and its hash owner otherwise. Overrides for
  members that go are dropped. The handoff is 0070's, unchanged: the new owner
  waits until the old one has let go.
- **Damping.** A shard moved is not moved again for `every × 3`, so load that
  moves with it does not swing back and forth.

## Why this shape

Overrides written by the leader keep placement a pure function of the agreed
view, which is the property 0070 depends on. Two nodes with the same view
always agree on the owner, and the handoff protocol needs no change. The
alternative is Pekko's shard coordinator: a singleton that allocates every
shard and is asked on every move. It is more flexible, but it is a single
point that must be recovered on failover, and lark avoided it in 0070 on
purpose. Recommended: leader-proposed overrides in the gossip.

## Stack

- [ ] **`spec-0090-load`** — per-shard load counted by each region and carried
      in the gossip. Done when: three nodes each see the others' entity counts
      per kind, and the counts are right after entities start and passivate.
- [ ] **`spec-0090-overrides`** — overrides in the gossip, and placement that
      honours them. Done when: an override set on the leader moves exactly that
      shard, through the usual handoff, and is dropped when its member goes.
- [ ] **`spec-0090-rebalance`** — `Rebalance.byLoad`, the leader's proposal,
      and damping. Done when: with one hot id range on one node of three, the
      busiest member's entity count falls within `tolerance` of the mean within
      three intervals, and no shard moves twice within its damping window.

## Acceptance

```bash
./gradlew :lark-cluster:build
```

## Open questions

- **Leader overrides in the gossip, or a coordinator singleton?** Recommended:
  overrides, as above.
- **What counts as load: running entities, messages, or both?** Recommended:
  both, compared as messages when there are any and as entities otherwise,
  since an idle entity costs memory and a busy one costs time.
- **Should a new member get shards by override at once, or only by hash?**
  Recommended: by hash, as now. Rebalancing then evens out what the hash left
  uneven, rather than racing the join.
- **The gossip grows per kind and shard.** Recommended: carry load only for
  shards a member owns, and only for kinds that rebalance.

Decided (2026-09-27): every open question goes as recommended. Placement
moves by overrides the leader writes into the gossip, not by a coordinator;
load is messages handled where there are any and running entities otherwise;
a new member takes shards by hash, as now; and the gossip carries load only
for shards a member owns, of kinds that rebalance.
