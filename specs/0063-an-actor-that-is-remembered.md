# 0063 — An actor that is remembered

## Problem

A `lark-actor` actor lives exactly as long as its flock, and its state lives
exactly as long as it does. A service with a million pets cannot keep a
million actors in memory, so it spawns one per pet on demand, keeps the refs
in a map by hand, stops the idle ones by hand, and races every message against
the stop it just began. When a pet's actor stops, restarts or the process
goes down, its state goes with it: the step writes to a database itself,
reads it back on start by hand, and has no way to say "these events happened,
in this order, exactly once".

0059 put keyed entities and persistence off to here. Transport, membership and
sharding stay in 0064–0066.

## Not doing

- **Anything across processes.** Entities of one type live in one flock. The
  id and the journal are shaped so that 0066 can place an entity on another
  node, and no further.
- **A durable journal.** `Journal` is an interface with an in-memory
  implementation; a JDBC or Kafka journal is its own spec once this one has
  settled the interface.
- **Snapshots.** Recovery replays every event. Snapshots come when a recovery
  is measured to be slow.
- **Projections and read models.** Reading the journal as a stream is 0067 if
  asked.
- **Persisted timers.** A timer still ends with the actor, as 0061 has it.

## Shape

```kotlin
// Entities: one actor per id, started on its first message, stopped when idle.
val pets = spawn("pets", entities<PetCommand>(passivateAfter = 10.minutes) { id -> pet(id) })

val rex: ActorRef<PetCommand> = pets.entity("rex")   // valid across passivation
rex.tell(Feed(grams = 50))

// A behaviour that is remembered: a command persists events, an event moves the state.
fun pet(id: String) = persistent<PetCommand, PetEvent, Pet>(
    id = PersistenceId("pet", id),
    empty = Pet.Unborn,
    codec = PetEvent.codec,
    command = { ctx, pet, command ->
        when (command) {
            is Feed -> persist(Fed(command.grams))
            is Weigh -> none().then { command.reply(pet.grams) }
            is Adopt -> if (pet.adopted) unhandled() else persist(Adopted(command.by)).then { command.reply(Ok) }
        }
    },
    event = { pet, event -> pet.after(event) },
)

// Time and storage belong to the flock; a test gets an in-memory journal it can read.
flock<Nothing, Unit> {
    journal(InMemoryJournal())
    …
}

testActors {
    val rex = spawn("rex", pet("rex"))
    rex.send(Feed(50))
    rex.restart()                                   // recovers from the journal
    rex.state shouldBe Pet.Fed(grams = 50)
    journal.events(PersistenceId("pet", "rex")) shouldBe listOf(Fed(50))
}
```

- **Entities.** `entities<M>(passivateAfter) { id -> behaviour }` is a
  behaviour: a manager that spawns the entity for an id as its child on the
  id's first message and forwards to it. `entity(id)` answers an
  `ActorRef<M>` that goes through the manager, so it stays valid while the
  entity comes and goes. An entity idle for `passivateAfter` is stopped by
  the manager, which keeps any message for it that arrives meanwhile and
  starts it again on the first. Ids are strings.
- **Persistent behaviours.** `persistent(id, empty, codec, command, event)` is
  a `Behaviour<M, S, E>` like any other. On start it replays its events from
  the flock's journal through `event` before its first command; commands that
  arrive meanwhile wait. A command answers an `Effect`: `persist(events…)`,
  `none()`, `unhandled()` or `stop()`, and `.then { state -> }` runs after
  the events are written and applied.
- **Persisting blocks the step.** The step's virtual thread waits for the
  journal to answer, then applies the events, then runs `then`. Nothing is
  stashed and nothing is reordered: the next command sees the new state.
- **The journal.** `Journal` has `append(id, expected, events)` and
  `read(id, from)`. `append` takes the sequence number the writer expects to
  follow, and a mismatch is a `Conflict` that fails the step, so two writers
  for one id cannot both succeed. Events cross it as bytes through the
  behaviour's `EventCodec`, in memory too, so a missing or broken codec fails
  a test rather than production.
- **Failure.** A failed append fails the step, and supervision decides; a
  restart replays the journal, so the state is exactly what was written.
- **Tests.** `testActors` has an `InMemoryJournal` by default, readable as
  `journal`. `TestActor.restart()` restarts an actor as a failure would, so
  a test can say "and after a restart, the state is the same".

## Why this shape

Persisting on the step's own thread is what virtual threads buy: Pekko's
`persist` returns before the write and stashes every command until it lands,
because a dispatcher thread cannot wait; here the step waits, and ordering is
the ordering of the code. Entities are a manager actor rather than runtime
support because passivation has to hold a message that arrives while its
entity stops, and an actor that owns the entities is the one place that sees
both. Strings as ids, and bytes through a codec, are the two things 0066's
sharding and a durable journal need, and adding them later would change every
entity and every journal.

## Stack

- [ ] **`spec-0063-entities`** — `entities`, `entity(id)`, passivation.
      Done when: one id is one actor, two ids are two, and an idle entity
      stops and comes back on its next message without losing one sent while
      it stopped, on both runtimes.
- [ ] **`spec-0063-journal`** — `Journal`, `InMemoryJournal`, `EventCodec`,
      `Flock.journal` and the test kit's `journal`. Done when: an append with
      the wrong expected sequence number is a `Conflict`, and a read answers
      what was appended, in order.
- [ ] **`spec-0063-persistent`** — `persistent`, `Effect`, recovery, and
      `TestActor.restart()`. Done when: an actor restarted, or passivated and
      started again, comes back with the state its events built, on both
      runtimes, and a command sent during recovery is handled after it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Ids: strings, or a type parameter?** Recommend strings: sharding needs to
   hash and send an id, and a typed id would need a codec of its own for no
   gain a `value class` wrapping the string does not give.
2. **Passivation: through the manager, or in the runtime?** Recommend the
   manager, which stops an idle entity and keeps what arrives for it until it
   has stopped. An idle stop in the runtime would race a message told in the
   same instant, and dead-letter it.
3. **Does `persist` block the step?** Recommend yes, as above. The cost is one
   parked virtual thread per writing actor, which is what they are for.
4. **Bytes through a codec, or objects in the journal?** Recommend bytes, in
   the in-memory journal too, so a test fails on the codec a real journal
   would need. The codec is an interface, `encode(event): ByteArray` and
   `decode(bytes): event`, with no serialisation library chosen here.
5. **What does a `Conflict` do?** Recommend it fails the step like any append
   failure: a restart replays what the other writer wrote, and the command's
   sender, if it asked, hears `Stopped` or a timeout rather than a wrong
   answer.
