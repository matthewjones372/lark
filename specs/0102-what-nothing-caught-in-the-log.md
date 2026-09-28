# 0102 — What nothing caught, in the log

## Problem

An actor that throws with no supervision stops, and the throw goes on to its thread's handler (the runtime's rule).
In an application, that handler was the JVM's default one, which prints a bare stack trace to stderr. The trace is
not a log line, so an operator's log shipping, levels and error counts never see it. When a journal database went
away in lark-bank's Docker chaos run, every account entity with an append in flight threw the same
`SQLTransientConnectionException`. One node printed 8,000 traces in a minute, and its log showed no errors at all.

## Not doing

- **No change to the runtime's rule.** A throw still stops the actor and still reaches the thread's handler.
  Tests and applications that set their own handler keep it.
- **No supervision by default.**

## Shape

`runApp` sets a default uncaught-exception handler for as long as the application runs, unless one is already set.
The handler logs through lark's `logError`, with the stack. The first failure of each kind (its class and the frame
it was thrown from) in a minute is logged. The rest of that kind in the minute are counted, and the count is said
with the next one after it:

```
ERROR nothing caught this on a virtual thread: java.sql.SQLTransientConnectionException: journal-db-1 - …
ERROR nothing caught this on a virtual thread: java.sql.SQLTransientConnectionException: … (2893 more like it in the 1m before)
```

## Why this shape

In the log, the failure counts as an error where alerts and dashboards already look. Logging only the first of
each kind keeps a database outage from burying everything else. It still says how many there were, so the size of
the outage is not lost. The alternative, logging every one, is what stderr did, and it made the log useless during
exactly the minute it was needed.

## Stack

- [x] **`claude/uncaught-to-log`** — `Uncaught`, and `runApp` setting it.
      Done when: a throw reaches the log as an error with its stack, and a thousand of the same kind within a
      minute log once, with the count said by the next one after the minute.

## Acceptance

```bash
./gradlew :lark-app:check
```

## Open questions

1. **Should the count be said when the minute ends, even if nothing of that kind comes after?** Recommended: not
   yet. That needs a timer thread in lark-app. The count is said once the kind is seen again, which is the case
   that matters: an outage that is still going on.
