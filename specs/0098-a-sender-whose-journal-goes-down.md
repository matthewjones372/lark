# 0098 — A sender whose journal goes down

## Problem

A durable producer (0085) writes each command to the journal before `send`
returns. When the journal throws, because the database is down or a
connection is lost, the producer never recovers. A probe on `main`, with a
journal that throws for four sends and then works again, shows this:

- **The producer's actor stops.** The append's `SQLException` is thrown from
  the persistent step, and a throw stops an actor that has no supervision. It
  is logged as an uncaught exception on an unnamed runner thread.
- **Every later `send` waits out `within` and answers `Full`.** Each one is a
  dead letter to the stopped actor, so the caller waits the whole timeout and
  is told the producer has too much outstanding, which is not the problem.
- **It stays that way after the journal is back.** All three sends after the
  database returned also answered `Full`. Only restarting the node brings the
  producer back.
- **Room leaks.** A command takes its room before the write. When the write
  throws, the room is never released, so a producer with a small `keep`
  would answer `Full` at once even with a working actor.

A service cannot tell "slow down" from "the database is down", and one
outage takes its outbox down until a restart.

## Not doing

- **Keeping commands in memory while the journal is down.** A durable `send`
  promises the command is written when it returns. A buffer would break that
  promise at the one moment it matters.
- **Retrying inside `send`.** The caller decides whether to retry, queue or
  fail its own request. The producer only answers promptly and truthfully.
- **Persistent entities in general.** An entity's step that throws keeps
  today's supervision; a service picks `restart` for it. This spec is about
  the producer, whose actor the service does not write.
- **The in-memory producer.** It has no journal.

## Shape

```kotlin
when (val sent = payments.send("w-42") { Pay(10, it) }) {
    is Either.Right -> accepted()
    is Either.Left -> when (val why = sent.value) {
        Full -> slowDown()                         // too many unconfirmed, as today
        is Unwritten -> unavailable(why.cause)     // the journal refused it; nothing was kept
    }
}
```

- **`NotSent`.** `send` answers `Either<NotSent, Unit>`, and `NotSent` is a
  sealed interface with two cases. `Full` is one of them, unchanged.
  `Unwritten(cause)` means the journal threw and the command was not kept.
- **The producer survives.** A journal throw in the producer's step answers
  the waiting `send` with `Unwritten` at once, releases its room, and
  restarts the actor. The producer is spawned with
  `restart = Schedule.exponential(100.milliseconds)`, capped at 5 s through
  `delayed`. The restart replays the journal, so it comes back with exactly
  what was written.
- **Prompt while down.** A restart keeps the mailbox, so a command told
  during the backoff would wait it out. From the first failure until a replay
  succeeds, `send` answers `Unwritten` straight away rather than telling the
  actor and waiting `within`.
- **A lost confirmation is harmless.** A `Done` write that throws leaves the
  command unconfirmed. After the restart it is resent, and the entity drops
  the duplicate (0079).
- **Seen.** `lark.delivery.unwritten` counts each `Unwritten`. A warning is
  logged once when the journal first fails, and one line when it recovers,
  not once per send.

## Why this shape

The producer is lark's actor, not the service's, so lark has to supervise it;
a stopped outbox that nobody restarts is never the right answer. Answering
`Unwritten` rather than blocking follows 0079's rule that a producer's back
pressure is answered, never hidden. A separate case, rather than `Full`, lets
the service tell a 503 from a 429. The alternative is to throw from `send`.
That is louder but pushes a try/catch into every caller, where an `Either`
already has a place for it. Recommended: the sealed `NotSent`, and a
restarting outbox.

## Stack

- [x] **`spec-0098-survive`** — the durable producer's actor restarts with
      backoff on a journal throw, and a command's room is released when its
      write fails. Done when: with a journal that throws for four sends and
      then works, the next send after it recovers is written and delivered,
      and `drain` shows all `keep` room free again. On `main` both fail.
- [ ] **`spec-0098-unwritten`** — `NotSent`, `Unwritten(cause)`, the prompt
      answer while down, the counter and the two log lines. Done when: a send
      while the journal is down answers `Unwritten` in under a tenth of
      `within`, and one after it recovers answers `Right`.
- [ ] **`spec-0098-guide`** — the cluster guide's reliable delivery section
      shows the `when` above. Done when: that example compiles in
      `GuideExampleTest`.

## Acceptance

```bash
./gradlew :lark-actor:test --tests '*DurableProducer*' :lark-cluster:test --tests '*GuideExample*'
```

## Open questions

1. **`Either<NotSent, Unit>`, or keep `Full` and throw for a journal
   failure?** Recommended: `NotSent`. Code that names `Either<Full, Unit>`
   has to change its type, and lark-bank's `BankNode.transfer` is one such
   place. Code that matches on `Left` keeps compiling.
2. **How long is the backoff?** Recommended: 100 ms doubling to 5 s, and
   not configurable yet. That is short enough that a blip costs little, and
   long enough that a dead database is not hammered.
3. **Should a replay that succeeds clear "down", or only an append that
   succeeds?** Recommended: a replay. It is the first proof the journal is
   reachable, and the next send's append settles the rest.

Decided (2026-09-27): every open question goes as recommended. `send`
answers `Either<NotSent, Unit>`, with `Full` and `Unwritten(cause)` as its
cases; the backoff is 100 ms doubling to 5 s, not configurable yet; and a
replay that succeeds clears "down".

Decided while building `spec-0098-survive`:
- **Where the catch sits.** The producer's persistent behaviour is wrapped so a step that throws tells the outbox which message it was on, then throws on to supervision unchanged. A raise, such as a journal conflict, goes on untold.
- **Room.** `send` takes the room before the write, so the outbox gives it back when the step throws before the write lands. A command counts as written, and holds its room, from the first line of its `then`, so a throw after the write keeps the room for the replay to find.
- **The backoff.** `Schedule.exponential(100.milliseconds)`, capped at 5 s through `delayed`, is used by the flock's and `testActors`' producer alike.
- **The test.** On `testActors`, with a journal whose producer appends throw four times and a `keep` of 4, the fifth send is written and delivered, and `drain` is true. On `main` the first throw reaches the test. With the restart but without the room given back, the fifth send answers `Full`.
