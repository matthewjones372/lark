# 0079 — A message that arrives when its entity moves

## Problem

0070 says a message already in an entity's mailbox when its shard moves is a
dead letter, and 0063 says the same of passivation. That is at-most-once, as
0068 promised, and for a command such as `Pay(10)` it means the payment is
silently lost whenever a node joins, leaves or restarts. Today a service that
cannot lose a command resends it itself: it keeps what it sent, waits for an
answer, sends again, and teaches every entity to ignore a command it has
already applied. Every service writes that loop, and gets the retry timing or
the deduplication wrong in its own way.

## Not doing

- **Exactly once.** A resend after a lost confirmation can deliver twice; the
  entity side drops duplicates it remembers, and a persistent entity
  remembers them across restarts and moves. An entity that is not persistent
  can see a command twice after a move.
- **A durable outbox.** What the sender has not had confirmed lives in the
  sender's memory; a sender that crashes loses it. A journal-backed outbox is
  its own spec if asked.
- **Ordering across senders.** Each sender's commands to one entity arrive in
  the order it sent them; two senders are not ordered against each other.
- **Changing plain `tell`.** At-most-once stays the default; reliable delivery
  is asked for.

## Shape

```kotlin
// On the sending side: a producer per sender, over the sharded entities.
val payments = orders.reliable(producerId = "checkout-1", resendAfter = 2.seconds, keep = 1_000)
payments.send("o-42", Pay(10))            // returns once the command is kept; delivered at least once

// On the entity side: the behaviour is wrapped, and sees each (producer, sequence) once.
val orders = cluster.sharding("order", OrderCodec.delivered(), passivateAfter = 2.minutes) { id ->
    delivered(order(id))                  // confirms after the step that handled it
}
```

- **Sending.** A producer numbers each command per entity, keeps it until
  the entity confirms it, and sends it again every `resendAfter` until then.
  A move or a passivation loses only a copy. `send` blocks while more than
  `keep` are unconfirmed, and fails with `Full` after `within`, so a slow
  entity pushes back rather than filling memory.
- **Receiving.** `delivered(behaviour)` unwraps each command, drops one whose
  sequence number it has already handled from that producer, runs the step,
  and confirms once the step has returned: after the events are written, for
  a persistent behaviour. For a persistent entity the last sequence number
  per producer is kept in its state, so deduplication survives moves.
- **The wire.** A delivered command crosses as the producer's id, the
  sequence number and the command in the kind's own codec; the confirmation
  is a small message back to the producer's address.

## Why this shape

Acknowledge-and-resend with per-producer sequence numbers is the smallest
thing that turns "lost on a move" into "late on a move", and keeping the
highest sequence number per producer in a persistent entity's state makes
the duplicates it causes harmless where they matter most. The alternative is
Pekko's reliable delivery, with producer, consumer and sharding controllers
and a flow-control protocol. It is more general and much more to learn.
Recommended: one producer per sender, one wrapper per entity.

## Stack

- [x] **`spec-0079-receive`** — `Delivery`, `Delivered`, `Confirmed` and
      `delivered(behaviour)`, in `lark-actor`. Done when: an entity confirms
      each delivered command after its step, a plain one never, and a step
      that fails confirms nothing.
      ([#206](https://github.com/matthewjones372/lark/pull/206))
- [x] **`spec-0079-dedup`** — deduplication in `persistent`. Done when: a
      persistent entity sees a command sent twice once, confirms both, and
      still drops the duplicate after a restart, from a snapshot or a replay.
      ([#207](https://github.com/matthewjones372/lark/pull/207))
- [ ] **`spec-0079-send`** — the producer: numbering, keeping, resending,
      pushing back. Done when: in one flock, with the entity stopped and
      started while commands are sent, every command is handled, and `send`
      fails with `Full` when `keep` are unconfirmed.
- [ ] **`spec-0079-sharded`** — `reliable(…)` on `Sharded` and the codec,
      across nodes. Done when: three nodes, with one leaving while payments
      are sent to 200 persistent entities, end with every payment applied
      exactly once.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Keep deduplication state in a persistent entity's state, or in a
  separate store?** Recommended: in its state, so it is written with the
  events it guards and needs no second write.
- **Confirm after the step, or after `then`?** Recommended: after the step,
  which is after the events are written; `then` is the entity's business.
- **Push back by blocking `send`, or by failing at once when full?**
  Recommended: block up to `within`, then fail with `Full`, as an ask does.

Decided (2026-09-27): every open question goes as recommended. A persistent
entity keeps its deduplication state in its own state; an entity confirms
after its step; and `send` blocks up to `within`, then fails with `Full`.

Decided while building `spec-0079-receive`: the stack splits in four, since
deduplication changes `persistent` and confirmation does not. An entity's
commands stay its own type: a reliable command implements `Delivered` and
carries its `Delivery`, so the entity's timers, stash and state are
untouched, and `delivered(behaviour)` only confirms once the step returns. A
wrapper that confirmed before the step fails the test of a failing step.

Decided while building `spec-0079-dedup`: `Remembered` gains `delivered`, the
last sequence number per producer, with an empty default. A delivered command
that persists events appends a mark of its delivery after them in the same
append, so the mark is written exactly when the events are; replay reads marks
back into `delivered`, and a snapshot carries it ahead of the state's own
bytes, which stay the service's alone when nothing was delivered. A command
that persists nothing writes no mark, and after a restart is handled again,
where it changes nothing the journal holds. `Journal.events` and `follow`
skip marks, and a follower's offset passes them. Dropping the map from the
snapshot, or the marks from replay, fails the tests.
