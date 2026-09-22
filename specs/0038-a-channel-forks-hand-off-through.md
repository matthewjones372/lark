# 0038 — A channel forks hand off through

## Problem

Two forks in a `flock` can only hand values over through what `Deferred` holds:
one answer, once. A producer and a consumer — rows read on one fork and written
on another, with at most N in flight — means reaching for `ArrayBlockingQueue`.
It has no close, so the end of the data is a sentinel value. It has no
cancel, so a consumer that stops early leaves the producer parked in `put`
until the scope interrupts it, and its interrupt then answers the scope. It also
has no way to wait on a queue and a `Deferred` at once, so a consumer that
should stop at a deadline polls.

This is also the piece a stream without Pekko needs (0039): a bounded handoff is
backpressure.

## Not doing

- **No errors through the channel.** A `Raise` never crosses a thread. A
  producer that raises fails its fork, and the fork answers its scope as it does
  today. The channel only closes.
- **No unbounded channel and no broadcast.** Capacity is required. A second
  reader is a second channel.
- **No channel outliving its scope.** It is opened by a `Flock` and closed when
  that scope closes, like its forks.

## Shape

```kotlin
flock {
    val rows = channel<Row>(capacity = 64)
    async { db.scan(query) { row -> rows.send(row) }; rows.close() }
    for (row in rows) sink.write(row)          // ends at close
}

flock {
    val stop = async { Thread.sleep(budget.toMillis()) }
    select {
        rows.onReceive { process(it) }
        stop.onAwait { Done }
    }
}
```

- `Flock<E>.channel<A : Any>(capacity: Int): Channel<A>`. The element type is not
  null, as in `lark-stream`.
- `send` parks while the channel is full. `receive` parks while it is empty and
  answers `null` once closed and drained. The channel is `Iterable<A>`.
- `close()` is the producer's: a later `send` throws `IllegalStateException`.
- `cancel()` is the consumer's: every parked and later `send` answers `false`
  and stops, so a producer loop ends without an interrupt.
- `select` parks once on every clause and runs the first to become ready.
  A clause is `Channel.onReceive`, `Channel.onSend` or `Deferred.onAwait`.

## Why this shape

The channel uses the same primitive as 0037's `Fork`: one state reference and
waiters that park in it. `select` then costs nothing new. It pushes one waiter
onto each clause's state and parks once, where polling or a thread per clause
would be the alternatives.

The alternative is a wrapper over `ArrayBlockingQueue` with a close flag. It
covers `send`, `receive` and `close`, but not `select`, because the queue keeps
its waiters inside a `Condition` nothing else can wait on. Recommended: our
own.

## Stack

- [ ] **`spec-0038-channel`** — `channel`, `send`, `receive`, `close`, `cancel`,
      iteration, and the scope closing it.
      Done when: a producer 1000 rows ahead of a slow consumer never holds more
      than `capacity`, and a consumer that cancels ends its producer without an
      interrupt reaching the scope.
- [ ] **`spec-0038-select`** — `select` over channels and `Deferred`.
      Done when: a `select` on an empty channel and a fork answers with the fork,
      and the losing clause's waiter is gone afterwards.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `send` after `cancel` answer `false`, or raise?** Recommended: return
   `false`. Stopping is the normal end of a producer, not an error.
2. **Capacity zero (a rendezvous)?** Recommended: allowed. It is the same state
   machine with no buffer.
3. **Is `select` fair?** Recommended: the first clause written wins a tie, as in
   Go's `select` without the randomness, so a test can rely on it.
4. **Can `Flight` (0037 question 2) be rebuilt on `select`?** Recommended: yes,
   as its own entry once both pieces are in.
