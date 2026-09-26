# 0070 — An entity on whichever node owns it

## Problem

0059's `entities` runs one actor per id, but only inside one flock, and 0069's
cluster agrees who is up without placing anything on them. A service with more
entities than one node holds, or that must keep running when a node goes, has
to pick a node per id itself, route to it, and move the entity when that node
leaves, and it gets two copies of an entity the moment it moves one too early.
Today that is Pekko Cluster Sharding and its coordinator.

## Not doing

- **A coordinator.** No singleton that allocates shards; every node computes
  the same placement from the agreed view.
- **Rebalancing by load.** Placement follows membership only.
- **Remembered entities.** An entity starts on its first message after a move,
  as it does after passivation; 0063's journal restores its state.
- **Roles and data centres.** Every `Up` member hosts shards and singletons.

## Shape

```kotlin
val orders = cluster.sharding("order", OrderCodec, passivateAfter = 2.minutes) { id -> order(id) }
orders.entity("o-42").tell(Pay(10))          // from any node; reaches the one node that runs o-42
orders.entity("o-42").ask(5.seconds) { Total(it) }

val clock = cluster.singleton("billing-clock", ClockCodec) { billingClock() }
clock.tell(Tick)                             // on the oldest Up member, wherever that is now
```

- **Placement.** An id's shard is a stable hash of it, modulo a fixed number
  of shards per kind (default 256). A shard's owner is the `Up` member with
  the highest rendezvous hash of `(kind, shard, member)`, so a join or a leave
  moves only the shards that member gains or loses, and every node agrees
  without asking.
- **Region.** Each node runs one region per kind: it tells a message for a
  shard it owns to that shard's local `entities`, and sends the rest through
  0068's transport to the owner's region, in an envelope of kind, id and the
  codec's bytes.
- **Handoff.** When the view changes, a node that loses a shard stops its
  entities there, then tells the new owner it has let go. The new owner keeps
  what arrives for that shard until it hears so, or until the old owner is
  `Removed`, so an entity never runs on two nodes. What is kept is bounded,
  and what overflows is a dead letter.
- **Singleton.** A singleton runs on the oldest `Up` member. When that member
  leaves or is downed, the next oldest starts it once the old one has stopped
  it or is removed, by the same handoff. `singleton(…)` returns a ref that
  finds the current one.

## Why this shape

Rendezvous hashing over the agreed view needs no coordinator and moves as
little as possible; the cost is that placement cannot follow load, and that a
view in flux can briefly route to an owner that is about to lose the shard,
which the handoff buffers rather than drops. The alternative is Pekko's: a
coordinator singleton that allocates shards and can rebalance. It is more
flexible and far more state to keep consistent. Recommended: rendezvous.

## Stack

- [x] **`spec-0070-placement`** — shard of an id, owner of a shard from a
      view. Done when: a join moves at most about 1/N of the shards, and the
      same view gives every node the same owners.
      ([#168](https://github.com/matthewjones372/lark/pull/168))
- [x] **`spec-0070-region`** — regions, the envelope, and `sharding(…)`.
      Done when: three nodes in one JVM tell and ask one entity from any node,
      and it runs on the owner only.
      ([#169](https://github.com/matthewjones372/lark/pull/169))
- [x] **`spec-0070-handoff`** — letting go of a shard when the view changes.
      Done when: a node joining and one leaving while entities are told
      throughout never has one entity running on two nodes, and loses no
      message that the handoff kept.
      ([#170](https://github.com/matthewjones372/lark/pull/170))
- [x] **`spec-0070-singleton`** — `singleton(…)` and its ref. Done when: the
      oldest member leaving moves the singleton to the next, with no moment
      where both run it.
      ([#171](https://github.com/matthewjones372/lark/pull/171))

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Rendezvous hashing, or a coordinator singleton (Pekko's shape)?**
  Recommended: rendezvous; a coordinator can come later if load-based
  rebalancing is needed.
- **Number of shards: fixed per kind, the same on every node?** Recommended:
  yes, default 256; changing it is a full restart, as in Pekko.
- **Do `Joining` members host shards?** Recommended: no, only `Up` ones, so
  placement changes only when the leader moves a member.
- **Where does sharding live?** Recommended: in `lark-cluster`, since it needs
  nothing beyond the view and the transport.

Decided (2026-09-26): every open question goes as recommended. Placement is
rendezvous hashing over the agreed view, with no coordinator; the number of
shards is fixed per kind, 256 by default and the same on every node; only `Up`
members host shards; and sharding lives in `lark-cluster`.

Decided while building `spec-0070-handoff`: a node that wins a shard asks every
other `Up` or `Leaving` member to release it, rather than only the one it
thinks held it, since two view changes close together can name a previous
owner that never ran the shard. A member releases only once it neither runs
the shard nor believes it owns it. A message that runs out of hops is routed
again after 100 ms rather than kept until the next view, which may never come.

Decided while building `spec-0070-singleton`: a singleton is a region of one
shard placed on the oldest `Up` member, whose host is the actor itself and
starts as soon as the shard is free. A message already in an entity's or a
singleton's mailbox when it moves is a dead letter, as it is on passivation;
what the handoff keeps is delivered.
