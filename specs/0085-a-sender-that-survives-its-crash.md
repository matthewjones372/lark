# 0085 — A sender that survives its crash

## Problem

0079's producer keeps what it has sent, and what no entity has confirmed yet,
in memory. 0080 made a producer that stops properly wait for all of it. A
producer that crashes still loses it: a checkout node killed by the kernel
mid-burst forgets the payments it accepted, and the caller was already told
they were accepted. Today the service keeps an outbox table itself, writes the
command there in the same transaction as its own change, and runs a loop that
sends from it — the loop 0079 was meant to spare it.

## Not doing

- **A transaction shared with the service's own writes.** The outbox is
  lark's journal. A service that needs "my row and the command, or neither"
  writes its row first, then sends; exactly-once across two stores is not
  offered.
- **Commands that cannot be encoded.** A durable producer keeps bytes, so its
  commands need the kind's `MessageCodec`, which a sharded kind already has.
- **Changing the in-memory producer.** It stays the default: cheaper, with no
  journal write per command, and right for commands a caller can resend.

## Shape

```kotlin
val payments = wallets.reliable("checkout", durable = true)   // kept in the flock's journal
payments.send("w-42") { Pay(10, it) }                          // returns once the command is written
```

- **Kept as events.** A durable producer is a persistent actor with
  `PersistenceId("lark-producer", id)`. `send` returns once a `Kept(to,
  sequence, bytes)` event is written, and a confirmation writes
  `Confirmed(to, sequence)`. Started again, on this node or another, it
  replays both and resends what is still unconfirmed.
- **A stable identity.** Its deliveries carry `id` alone, with no random
  incarnation. Entities' deduplication then carries across the producer's
  restart, and its numbering continues where it stopped. Two producers
  running under one `id` at once is the same conflict as two writers for one
  entity (0063): the second append fails.
- **Rebuilt on resend.** The command's bytes are stored with its delivery
  blanked. On a resend the producer decodes them and puts back a delivery
  that names itself now, through `Delivered.redeliver(delivery)`, which a
  command implements by copying itself. Its confirmations therefore reach the
  producer that is running, not one that crashed.
- **Bounded.** A snapshot every 1,000 events, pruning what the snapshot
  covers, so the outbox holds what is unconfirmed and not every command ever
  sent (0074, 0076).

## Why this shape

Keeping the outbox in the journal reuses everything a persistent entity
already has: a table every node reaches, conflicts between two writers, and
snapshots and pruning so it does not grow. It costs one append per command and
one per confirmation. The alternative is a table of its own, with rows deleted
as they are confirmed: less to replay, but a second store and its DDL, and
nothing to stop two producers under one id from both resending. Recommended:
the journal.

## Stack

- [x] **`spec-0085-redeliver`** — `Delivered.redeliver`, and the codec
      helpers that write and read a command with its delivery blanked. Done
      when: a command stored and read back with a new delivery equals the
      original but for the delivery.
      ([#233](https://github.com/matthewjones372/lark/pull/233))
- [x] **`spec-0085-durable`** — the durable producer in `lark-actor`: events,
      replay, resend with a rebuilt delivery, snapshots and pruning. Done
      when: on `testActors`, a producer stopped with 50 unconfirmed commands
      and started again under the same id delivers all 50 once, and numbers
      the next command after them.
      ([#234](https://github.com/matthewjones372/lark/pull/234))
- [x] **`spec-0085-sharded`** — `reliable(…, durable = true)`. Done when:
      three nodes, the producer's node crashed mid-burst and its producer
      started on another, end with every accepted payment applied exactly
      once.
      ([#235](https://github.com/matthewjones372/lark/pull/235))

## Acceptance

```bash
./gradlew build
```

## Open questions

- **The journal, or an outbox table of its own?** Recommended: the journal,
  as above.
- **How is a command rebuilt with a new delivery?** Recommended:
  `Delivered.redeliver(delivery)`, implemented by a data class as
  `copy(delivery = delivery)`. The alternative, keeping the `(Delivery) -> M`
  lambda, cannot be written to a journal.
- **Who starts the producer again after its node crashes?** Recommended: the
  service, by calling `reliable(…, durable = true)` with the same id on
  whichever node it chooses. A singleton (0070) is the natural place when
  exactly one should run.
- **Does `send` wait for the journal write?** Recommended: yes, so "sent" means
  "will arrive". The cost is one append on the caller's thread, the price of
  durability.

Decided (2026-09-27): every open question goes as recommended. The outbox is
the flock's journal; a command is rebuilt through `Delivered.redeliver`; the
service starts a crashed producer again under its id; and `send` returns once
the command is written.

Decided while building `spec-0085-redeliver`: `Delivered.redeliver` has a
default that throws, naming the command, so every command sent only by a
producer in memory compiles unchanged. A blank delivery confirms to
`Delivery.NoOne`. The helper is `MessageCodec.outbox()`, in
`lark-actor-remote` beside `delivery`, and it answers an `EventCodec`, which
is what a persistent actor's events need. It refuses a kept command that
carries a reply or any ref but a blank delivery's, since nothing would answer
either after a crash. Storing a command without blanking it fails the tests.

Decided while building `spec-0085-durable`:
- **Where it lives.** `durableProducer(id, codec)` sits on a flock and on `testActors`, beside `producer`, and answers the same `Producer`.
- **What it keeps.** It is a persistent actor whose events are `Kept`, holding the command's bytes, and `Done`. It sends each entity's first unconfirmed command at once after a start, rather than a `resendAfter` later.
- **Room.** Only commands kept in this life hold room: one recovered from the journal was never given any, so its confirmation frees none, and room never exceeds `keep`.
- **What `send` does.** It waits up to `within` for the write, and answers `Full` if the write takes longer. Such a command may still be written and sent, which the KDoc says.
- **Pruning.** It keeps no more than the 1,000 events since the snapshot before the newest.
- **What the tests catch.** A producer without the immediate send after a start fails them, and so does one whose id changes between lives.

Decided while building `spec-0085-sharded`:
- **Room for what is recovered.** This entry changed one thing the last one decided. A durable producer started again now claims room for what it recovered, as far as `keep` allows, so `drain` waits for those commands too. The claim is made on its first message, and `drain` waits for it, so it cannot answer "drained" before the actor has looked.
- **What the test found.** Its first version drained in 14 ms, before that claim was made. Every payment had been confirmed before the crash, so it proved nothing.
- **How the test works now.** Accounts run only on a `ledger` node, and none is up until the crash. The test asserts that all 400 payments are kept and none confirmed when the node dies, then starts a ledger and the successor, and finds each payment applied once.
- **What it catches.** A successor that is not durable, or a drain that does not wait for the claim, fails it.
- **The guide.** Its reliable-delivery section names `durable = true`.
