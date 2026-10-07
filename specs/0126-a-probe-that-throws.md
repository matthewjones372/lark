# 0126: A probe that throws is a probe that failed

## Problem

A probe answers `true` or `false`, and both places that ask one assume it
will. Neither catches a throw.

- **`HealthRegistry.readiness()`** (`Health.kt`). A throw from one probe
  escapes `readiness()`, so the caller gets an exception instead of `Down`.
  The petshop found this by stopping its Postgres:
  - its `database` probe borrows a connection, and Hikari throws
    `SQLTransientConnectionException` when none comes;
  - so `/health` answered a 500 instead of `{"ready": false, "failing":
    ["database"]}`;
  - the scrape that reads health failed with it, so the alert that should
    have said "the database check is failing" never fired.
- **Starting the graph** (`Start.kt`, `answers`). A throw ends the start with
  the exception itself, on the first attempt. A probe declared with
  `attempts = 10, interval = 500.milliseconds` is retried only when it
  answers `false`. A pool whose database is still coming up throws, so it is
  never retried, and the caller gets an exception rather than
  `StartupError.Unready` naming the node.

Today every probe that can throw has to catch for itself. The petshop's now
does:

```kotlin
.probe("database", timeout = 3.seconds) { pool: DataSource ->
    try { pool.connection.use { it.isValid(2) } } catch (_: SQLException) { false }
}
```

Nothing tells the next author they need that `try`.

## Not doing

- Changing what a probe is: a question answered `true` or `false`, inside
  its timeout.
- Catching `Error`s. A probe that runs out of memory is not a probe that
  failed.
- Logging. A failing probe is already reported by name, and a
  service decides what it logs.

## Shape

No new API for a probe's author. Both places that ask a probe treat a throw
as `false`:

```kotlin
// Health.kt and Start.kt, one function both call
internal fun Probe.answered(value: Any): Answer =
    try {
        if (timeoutOrNull(timeout) { ask(value) } == true) Answer.Yes else Answer.No(null)
    } catch (thrown: Exception) {
        Answer.No(thrown)
    }
```

- **`readiness()`.** A probe that throws is in `failing`, like one that
  answered `false`. It is never an exception from `readiness()`.
- **Starting.** A throw is retried like a `false`, up to `attempts`, and when
  the attempts run out the start ends with `StartupError.Unready`.
  `Unready` gains `cause: Throwable?`, the last attempt's throw, so the
  start's failure still says why: "the pool could not get a connection",
  rather than only "not ready".
- **Interruption.** A probe that loses its timeout is interrupted. An
  `InterruptedException`, or a cancellation, is the timeout's doing, not the
  probe's answer, so it is not caught as a failure. It is left to the race
  that caused it.

## Why this shape

A probe is asked because nobody knows whether the thing is well. A throw is
the thing saying it is not, which is what `false` means. The alternative is
documenting that a probe must not throw. That puts a `try` in every probe that
touches the network, and the petshop shows what happens when one is missed:
the health endpoint fails at exactly the moment it is needed. Recommended:
catch in Lark, once.

## Stack

- [ ] **`spec-0126-probe-throws`**: one `answered` used by `readiness()` and
      by the start; `StartupError.Unready.cause`; `docs/app.md`'s probe
      section says a throw counts as failing.
      Done when:
      - in `HealthTest`, a critical probe that throws makes readiness `Down`
        naming it, and a non-critical one makes it `Degraded`;
      - in `ProbeTest`, a probe that throws twice and then answers `true`,
        with `attempts = 3`, starts the graph;
      - a probe that always throws ends the start with `Unready` whose
        `cause` is the throw;
      - `a wedged probe answers inside its timeout` still passes, with no
        interruption reported as a failure's cause.

## Acceptance

```bash
./gradlew :lark-app:check
```

Then the petshop's `database` probe drops its `try`, and stopping Postgres
still gives `/health` `{"ready": false, "failing": ["database"]}`.

## Open questions

- **Does `readiness()` say why?** `Health.Down` and `Degraded` carry names
  only. A `reasons: Map<String, String>` beside `failing` would let `/health`
  say "database: connection not available". Recommended: not here. Names are
  what an alert keys on, and a reason is a second, larger question about what
  a health endpoint may reveal.
- **`Exception` or `Throwable`, less `Error`s?** Kotlin code throws
  `Exception`s, but a Java driver could throw anything. Recommended:
  `Exception`, matching the rest of Lark's recovery code, and an `Error`
  stays fatal.
