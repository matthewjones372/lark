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

- [x] **`spec-0090-load`** — per-shard load counted by each region and carried
      in the gossip. Done when: three nodes each see the others' entity counts
      per kind, and the counts are right after entities start and passivate.
      ([#264](https://github.com/matthewjones372/lark/pull/264))
- [x] **`spec-0090-overrides`** — overrides in the gossip, and placement that
      honours them. Done when: an override set on the leader moves exactly that
      shard, through the usual handoff, and is dropped when its member goes.
      ([#265](https://github.com/matthewjones372/lark/pull/265))
- [x] **`spec-0090-rebalance`** — `Rebalance.byLoad`, the leader's proposal,
      and damping. Done when: with one hot id range on one node of three, the
      busiest member's entity count falls within `tolerance` of the mean within
      three intervals, and no shard moves twice within its damping window.
      ([#266](https://github.com/matthewjones372/lark/pull/266),
      [#267](https://github.com/matthewjones372/lark/pull/267))

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

Decided while building `spec-0090-load`:
- **Where load lives.** Each member has one `Load` in the gossip, with a version only it raises, merged as a digest is. It holds, per kind that rebalances, the shards with any entities or messages, which are the shards it owns. A kind with nothing running is carried as an empty entry.
- **The hash.** `Gossip.hash` leaves the load out, so a load that changes every interval never holds up the leader's convergence.
- **The wire.** The codec writes the load after the digests, in place, as 0083 decided. A cluster where no kind rebalances writes one empty map more per gossip.
- **Counting.** A region counts a message when it delivers it to a shard here, and a shard's entities through `entities`' `onRunning`. The cluster actor reads the counts on its tick once `every` has passed, and messages count from none again.
- **Who sees it.** `Cluster.balance` is published with the view, holds only live members, and wakes `await` as a view does. It is internal, and so is `Rebalance` until the next entry makes it public.
- **What the test catches.** Dropping the load from the merge or the codec fails it, and so does a count that does not fall when entities passivate.

Decided while building `spec-0090-overrides`:
- **Shape in the gossip.** Moves are kept per kind, as a version and a map from shard to the member's incarnation. The leader raises the version on each change, and a new leader starts from the highest it has merged. Two moves on the same version merge to the same one on every node.
- **One life only.** A move names the member's incarnation, not its address, so a node started again at the same address does not inherit the shards moved to its last life.
- **Agreed like membership.** `Gossip.hash` takes the moves in, so the leader moves members on only once every member places alike. A kind with no moves adds nothing, and the hash is unchanged without moves.
- **Only the leader writes them.** `Membership.move` does nothing on any other node, and ignores a move to a member that is not `Up`. A move to null sends a shard back to its hash owner.
- **Dropping.** Once converged, the leader drops every move to a member that is no longer `Up`. A `Leaving` member's moved shards go back to their hash owners as it leaves, as its own shards do. Placement ignores such a move even before it is dropped.
- **How a region learns them.** `Region.Viewed` carries its kind's moves, and `Placing` keeps them beside the view. Its handoff is unchanged. A change of load alone does not tell the regions anything.
- **What the tests catch.** Placement that ignores the move, or the incarnation, fails them. So do moves left out of the codec, a region not told when only the moves change, and a leader that never drops them.

Decided while building `spec-0090-rebalance`:
- **Split in two.** The entry came to about 330 changed lines, so it is two branches. This one has the proposal, damping and their unit tests. `spec-0090-rebalance-cluster` has the three-node test and the guide.
- **The API.** `rebalance` sits after `role` and before the trailing lambda on `sharding`, so every existing call compiles. `byLoad` defaults to one minute, 0.2 and 4, as in Shape. It refuses an interval that is not positive, a negative tolerance and `mostMoves` below one.
- **Where it runs.** The leader's `Balancer` runs on the cluster actor's tick, once per `every` for each kind, and writes through the last entry's `move`. A node that comes to lead waits one interval before it proposes.
- **The proposal.** It is a pure function. Load is messages if any member handled any in its last interval, and entities otherwise. Each shard counts on the member that owns it now, taking the larger of two reports while both owners still report it. The busiest member's heaviest shards go first, each to the least-loaded member. It stops at `mostMoves`, or once the busiest member is within `tolerance`. It makes a move only if the target ends up lighter than the busiest member, so one hot shard is never moved from member to member. A move back to a shard's hash owner drops its override.
- **Damping.** The leader keeps the time and load of each shard it moves. For `every × 3`, it does not move that shard again, and counts it at no less than the load it moved with. Without the second part, the three-node test moved shards back and forth, because a new owner's first report sees only part of a shard. A node that comes to lead damps every shard that is already moved.
- **What the tests catch.** Dropping the damping, the held load or the `mostMoves` limit fails a test. So does damping that expires at once.

Decided while building `spec-0090-rebalance-cluster`:
- **Where the box is ticked.** The rebalance entry is ticked here, where its done-when is shown on three nodes.
- **"Within three intervals".** The unit test checks it exactly: three of the leader's intervals. On three nodes it is checked as at most three rounds of moves before the entities are even, not as wall-clock time.
- **Steady traffic.** The test asks each entity once every 100 ms on a timer. With one sender asking in turn, a slow ask during a move held up the rest, so message counts no longer followed the entities, and the leader moved too much.
- **What the test catches.** Taking the rebalancing out of the cluster actor's tick fails it. With damping removed it failed one run in three, so the unit tests are what pin damping.
- **The guide.** Its Entities section has a paragraph on `rebalance`, and no new example.
