# 0146: one outbox protocol under both producers

## Problem

The outbox rules (spec 0079) are written twice. `producing` (`Producer.kt:237-293`)
and `OutboxSteps` (`DurableProducer.kt:221-324`) each implement them:

- send an entity its first command when its outbox was empty;
- take a `Confirmed` only for the head, then send the next;
- resend every head on a timer.

Each keeps its own `measure()`, and each repeats the comment "a copy that
finds the entity's mailbox full is not lost".

Room is where the copies part ways, and where the subtle bugs are:

- The durable producer recovers commands that may not all fit in room. It
  tracks which ones hold room (`holding`), claims room for the rest as room
  frees up (`claim`), and hands a confirmed command's room straight to a
  waiting one (`handOnRoom`), so a drain cannot slip in between.
- None of that can be tested except through a whole flock: `ProducerTest` is
  180 lines and `DurableProducerTest` 331.

## Not doing

- Moving numbering. The in-memory producer numbers on the caller's thread
  (`Numbering`), so a command is built with its own `Delivery`. The durable one
  numbers from its journal (`Stored.next`). Both stay as they are.
- Changing the journal format: `OutboxEvent`, `OutboxEventCodec` and
  `OutboxesCodec` are untouched, so existing journals replay.
- Changing `Producer`'s interface, or the `Keeping` / `Handed` seam, which
  already has two adapters (`Numbering`, `Encoding`).

## Shape

```kotlin
// lark-actor, internal, pure: no actor, no semaphore, no clock
internal data class Outboxes<C>(val queues: Map<String, List<Kept<C>>>) {
    fun kept(to: String, sequence: Long, command: C): Pair<Outboxes<C>, Send<C>?>
    fun confirmed(to: String, sequence: Long): Pair<Outboxes<C>, Send<C>?>?  // null: not the head
    fun heads(): List<Send<C>>                                               // what a resend sends
    val unconfirmed: Int
    val isEmpty: Boolean                                                     // spec 0144 retires on this
}

internal data class Send<C>(val to: String, val sequence: Long, val command: C)

// pure too; who holds room, for a producer whose recovered commands can exceed it
internal data class Holding(val held: Set<Pair<String, Long>>) {
    fun claim(outboxes: Outboxes<*>, free: Int): Pair<Holding, Int>          // how many it took
    fun confirmed(outboxes: Outboxes<*>, to: String, sequence: Long): Pair<Holding, Boolean> // released?
}
```

- The in-memory producer's state becomes `Outboxes<M>`.
- The durable producer's state becomes `Outboxes<ByteArray>`. Its `applied`
  folds `Kept` and `Done` events through `kept` and `confirmed`, so replay and
  live steps run the same code.
- Each actor's job shrinks to two things: performing the `Send`s it is given,
  and moving the semaphore by what `Holding` says.

## Why this shape

We agreed in chat to put room hand-on inside `Outboxes`. Reading the code
changed my mind: room is not part of the persisted state. A replayed command
holds no room until `Recovered` claims some, so a pure event fold (`applied`)
cannot know which commands hold room. Putting room in `Outboxes` would force a
"live or replaying" flag into persisted state.

A second small pure module, `Holding`, keeps the outbox state equal to what
the journal holds and still tests the room rules without a flock. The
in-memory producer can skip `Holding`, because every command it keeps holds
room.

## Stack

- [ ] **`spec-0146-outboxes`**: `Outboxes` and `Send`, with direct tests for
      head-only confirm, next-on-confirm and heads. `producing` runs on it.
      Done when: `ProducerTest` passes and `producing` has no queue logic of
      its own.
- [ ] **`spec-0146-durable`**: `Outboxes<ByteArray>` under `outbox`; `applied`
      folds through it.
      Done when: `DurableProducerTest` passes, and a journal written before
      this change replays (a fixture test).
- [ ] **`spec-0146-holding`**: `Holding` with direct tests: claim past room,
      hand-on, and a drain never answering while a recovered command waits.
      Done when: `OutboxSteps` is gone.

## Acceptance

```bash
./gradlew :lark-actor:test :lark-cluster:test
```

## Open questions

1. Should a `Send` carry the decoded command for the durable producer, or the
   bytes, decoded at send time as today? I recommend bytes: decoding stays at
   the edge, and `Outboxes` stays generic.
2. Does this land before or after 0144? I recommend after: 0144's retirement
   check becomes `outboxes.isEmpty`, and this spec only renames that check.
