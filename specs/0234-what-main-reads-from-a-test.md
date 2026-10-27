# 0234 — What main reads, from a test

## Problem

A surefold-junit test runs with `logger` and `metrics` bound to a capture of its own. That is right for nearly every
test, but it hides the one thing a few tests exist to check: what a `SurefoldLocal` answers where nothing is bound,
which is what `main` reads.

Two such tests, in two repositories, each get round it by running the read on a thread surefold didn't fork, where
nothing is bound:

- **surefold-slf4j's `OnTheClasspathSuite`** has `unbound { logger.get() }`, a `FutureTask` on a new virtual thread. It
  checks that the service file makes `Slf4jLogger` the logger with nothing bound.
- **petshop's `LoggingSuite`** has `asMainDoes { … }`, a platform-thread executor. It uses it twice:
  - to check that `logger.get()` is `Slf4jLogger`;
  - to run `testApp` so an adoption's log line reaches logback rather than the test's capture.

Each helper is a few lines that hop threads to drop every binding at once, the test's check record included. A check
made inside one fails: "a check made outside a test, or on a thread surefold does not carry the test's context to".
That limits each helper to reading a value and handing it back.

## Not doing

- **No change to what a test binds.** Every test still logs and measures into a capture of its own.
- **No surefold-junit helper that runs a block as `main` would.** Which bindings that should drop (logger and metrics,
  but not the check record or a story's transcript) is a guess this spec doesn't need to make.
- **No public `discoveredLogger` or `discoveredMetrics`.** One method on `SurefoldLocal` covers both, and the clock.

## Shape

```kotlin
// surefold-slf4j
unbound { logger.get() }.shouldBeInstanceOf<Slf4jLogger>()   // today
logger.unbound().shouldBeInstanceOf<Slf4jLogger>()          // after

// petshop
asMainDoes { testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(1), "Ada") } }   // today
logger.locally(logger.unbound()) { testApp<PetShop, Unit>(settled) { shop -> shop.adopt(PetId(1), "Ada") } }
```

- **`SurefoldLocal.unbound(): A`** answers what the local answers where nothing is bound: its initial value, the same
  thing `get()` answers on a thread with no bindings. For `logger` that is whatever the classpath registered, or
  `StderrLogger`; for `metrics`, the discovered registry; for `clock`, `SystemClock`.
- **`get()`** uses it, so the two can't drift.
- **surefold-slf4j's `OnTheClasspathSuite`** drops its `unbound` helper and calls `logger.unbound()`.
- **Docs:** `docs/testing.md`'s "Logs" bullet says how a test reads what `main` would, and `docs/observability.md`'s
  "Your own logger" names `logger.unbound()`. `llms.txt` and the surefold skill follow.

## Why this shape

The test needs one value, the unbound one, and a method that answers it says so without moving the test to another
thread. Binding it back with `locally` is the existing way to run a block with a given logger. So the second petshop
use needs nothing new, and its checks stay in the test's record.

**Alternative:** a surefold-junit `asMain { }` that drops the logger and metrics bindings for a block. It reads well,
but it has to pick which bindings `main` wouldn't have, and that list grows with every `SurefoldLocal` a module adds.
Recommended: `unbound()`.

## Stack

- [x] **`spec-0234-unbound`** ([#847](https://github.com/matthewjones372/surefold/pull/847)) — `SurefoldLocal.unbound()`, `get()` on it, a core test, surefold-slf4j's suite on it,
      and the docs.
      Done when: `:surefold:check` and `:surefold-slf4j:check` pass, and a test shows `logger.unbound()` is
      `StderrLogger` inside `logger.locally(…)`.
- [ ] **petshop `unbound`** — after a snapshot carries it, petshop's `LoggingSuite` drops `asMainDoes` for
      `logger.unbound()`.
      Done when: petshop's `LoggingSuite` passes with no executor in it.

## Acceptance

```bash
./gradlew :surefold:check :surefold-slf4j:check
grep -rn 'Thread.ofVirtual().start\|newThreadPerTaskExecutor' integrations/surefold-slf4j/src/test   # nothing
```

## Open questions

1. **Is it called `unbound()`?** Recommended: yes. It names the case, nothing bound, that `get()` falls back to.
   `initial()` says how it is built, and `asMain()` claims more than one local can know.
2. **Does `unbound()` answer the initial value fresh on each call, as `get()` does today?** Recommended: yes. `logger`
   and `metrics` already resolve their discovered value once and keep it, and a local whose initial value is made per
   read should stay that way.
