# 0095 — A busy entity that stops nothing

## Problem

A tell from inside an actor's step never waits for room: when the receiver's
mailbox is full, the tell throws, and the sender's step fails (0059). For a
service's own actors that is a choice they can see and handle. For lark's own
plumbing it is a trap:

- **A sharding region** tells each message to its shard's entity manager.
- **The manager** tells it to the entity.

Both are steps. A burst of more than 1,024 messages to one entity, which is
normal for a hot account under load, fills the entity's mailbox, then the
manager's. The manager's step fails and it stops, taking every entity of that
shard with it. Then the region's step fails and it stops, taking every shard
of that kind on the node. Every later message to the kind on that node is a
dead letter until the node restarts. The cluster benchmarks (0092) hit this
with a burst of 2,000 into one entity.

A topic's actor (0082) has the same shape when one subscriber is slow.

## Not doing

- **Changing what a tell from a step does.** It still never blocks an actor's
  thread; a service's own actors keep today's behaviour.
- **Unbounded mailboxes.** A burst must still be bounded somewhere, or a hot
  entity takes the node's memory.
- **Backpressure to the original sender across nodes.** A remote tell is at
  most once (0068). Reliable delivery (0079) is the answer for commands that
  must arrive, and it already resends what was dropped.

## Shape

- **The plumbing hands on with room, or keeps.** The region, the entity
  manager and a topic's actor hand a message on only if there is room for it.
  When there is none, they keep it, in order, behind whatever that shard or
  entity already has waiting, and try again when the receiver has taken some
  and on a short timer.
- **Bounded, then a dead letter.** Each keeps at most `KEEP_AT_MOST` messages
  per shard, entity or subscriber (the bound a region already has for a shard
  with no owner). Past it a message is a dead letter with a new reason,
  `DeadLetter.Why.Full`, and `lark.actor.dead_letters{reason="full"}` counts
  it. Nothing stops.
- **Order holds.** While anything is kept for an entity, everything after it
  for that entity is kept too, so a burst arrives in the order it was sent.
- **Seen.** `lark.sharding.buffered` (0081) already gauges what a region
  keeps. It now counts these too.

## Why this shape

Keeping, up to a bound, is what the region already does for a shard with no
owner yet, so it is one mechanism, not two. It turns an overload into
latency, then into counted dead letters that reliable delivery resends. Today
the same overload stops every entity of a kind on the node. The alternative, a
dead letter as soon as a mailbox is full, is simpler but drops a burst that
would have drained a moment later. Recommended: keep, bounded, then a dead
letter.

## Stack

- [ ] **`spec-0095-entities`** — the entity manager keeps rather than fails,
      and `DeadLetter.Why.Full`. Done when: 5,000 tells to one entity on
      `testActors` and on threads all arrive in order, and the manager never
      stops.
- [ ] **`spec-0095-region`** — the region keeps per shard. Done when: three
      nodes, a burst of 5,000 to one entity from another node, all applied in
      order and no region stopped. The 0092 sharding benchmark's burst goes
      back to 2,000.
- [ ] **`spec-0095-topic`** — a topic's actor keeps per subscriber. Done when:
      one stalled subscriber does not stop the topic, the others hear
      everything, and the stalled one's overflow is counted as `full`.

## Acceptance

```bash
./gradlew :lark-actor:test :lark-cluster:test
```

## Open questions

- **Keep, or dead-letter at once?** Recommended: keep, bounded, as above.
- **One bound for all three, or one each?** Recommended: one,
  `KEEP_AT_MOST`, which a region already uses; tuning it can come later.
- **A new `Why.Full`, or reuse `Unreachable`?** Recommended: a new reason. A
  full mailbox on this node is a different fault from a node that cannot be
  reached, and the metric should say which.
