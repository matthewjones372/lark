# 0131 — A last transaction its consumer cannot outrun

## Problem

A run whose source ends while its last batch commits, by `take`, `stop()` or a restart giving up, can end `Died`:
`CommitFailedException: Transaction offset Commit failed due to consumer group metadata mismatch: The coordinator is not
aware of this member`.

`Offset.group()` reads `leaving` once. Spec 0124 commits a leaving consumer's last transaction with the group id alone,
but nothing orders that read against the leave: the read can see the member, the consumer can then leave, and
`sendOffsetsToTransaction` reaches the broker for a member it no longer knows. The broker refuses it, the transaction
aborts, and a clean stop is reported as a failure. Two of 94 local `TransactTest` runs under load failed this way, on
Pekko, whose source closes on its own thread as the stream ends. A probe that hands over the member's metadata after
the group has emptied fails like this every time.

## Not doing

- **Holding the consumer open until downstream drains**, 0124's rejected alternative, rejected again.
- **Retrying the transaction.** See Why this shape.
- **The Pekko connector's handle.** It hands over no group today and still does not.

## Shape

The member check and the leave are ordered by one lock in `Commits`:

```kotlin
@KafkaSpi
interface Handle {
    fun handled()
    /** [send] with the group as it is now, under the lock the consumer's close takes. */
    fun <T> withGroup(send: (ConsumerGroupMetadata?) -> T): T = send(null)
}

// TransactionalProducer.commit
taken.withGroup { group -> client.sendOffsetsToTransaction(offsets, group ?: refused()) }
```

- `withGroup` holds the lock from reading `leaving` until `send` returns.
- `Commits.close()` takes the same lock before it sets `leaving` and closes the consumer.
- So the broker gets the member's metadata while it is still a member, or the group alone once it has gone.

## Why this shape

Three ways, recommended first:

1. **Order the read and the leave** (above). Exact, and nothing is sent twice. Its cost is a `close()` that waits for one
   `sendOffsetsToTransaction` at most, bounded by `max.block.ms`.
2. **Retry once.** On `CommitFailedException` from a handle now `leaving`, abort and run the transaction again with the
   group alone. Local to `commit()`, but every output is sent twice and the first copy aborted.
3. **Always the group alone.** Simplest, and gives up fencing a batch that straddles a rebalance, which 0124 wanted.

## Stack

- [ ] **`spec-0131-ordered-leave`**: `Handle.withGroup`, the lock in `Commits`, `commit` sending through it.
      Done when: a test that closes the consumer while a commit holds the lock sees the close wait, and the commit
      succeed as the member; a commit that starts after `close()` commits with the group alone; neither is `Died`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **`withGroup` replaces `group()`, or sits beside it?** `Handle` is `@KafkaSpi`. Recommend replacing it: a `group()`
   left behind is a way round the lock.
2. **Lands before or after 0130?** Independent code. Recommend after: once 0130's restart case ends after its commit,
   only `a record's outputs and its offset are committed together` still ends its source mid-commit, and this spec's own
   test is the one that pins the race.
