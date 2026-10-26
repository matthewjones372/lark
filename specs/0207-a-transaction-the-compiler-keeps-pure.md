# 0207 — A transaction the compiler keeps pure

## Problem

A `transact` body (spec 0206) can run more than once: each time a value it read changes first, and each time it
wakes from `waitForChange`. An HTTP call, a log line, an actor message or a write to a captured `var` inside it
happens once per run, not once per commit. Haskell's type system rules this out. On the JVM nothing does, so every
JVM STM leaves it to the docs, and the bug shows up only under contention, in production.

Surefold already ships a compiler plugin. `HandRolledChecker` in `surefold-app-compiler` flags `Thread.sleep` in
tests and hand-rolled schedulers, so the place for this check exists.

## Not doing

- **No runtime detection.** The JVM can't tell I/O from computation cheaply. The check is at compile time only.
- **No proof across modules.** The checker sees the call written in the body, not what a library function does
  inside. A function from another module is trusted.
- **No change for builds without the plugin.** They compile as today. The docs say the plugin is how you get the check.

## Shape

```kotlin
transact {
    val left = stock.get()
    log.info("reserving $n")          // error: SUREFOLD_EFFECT_IN_TRANSACT: Logger.info runs once per retry
    http.post(order)                  // error: java.net call inside transact
    count += 1                        // error: writes a var captured from outside the transaction
    stock.peek()                      // error: reads outside the transaction; use get()
    async { … }                       // error: forks inside transact
    stock.set(left - n)               // fine
}

// A function that takes the Transaction receiver is checked as if it were a body.
fun Transaction.take(n: Int) { … }
```

What the checker reports, inside a `transact` lambda or a function taking `Transaction`:

- calls into `java.io`, `java.net`, `java.nio.channels`, `java.sql` and `java.net.http`;
- `Thread.sleep`, `Deferred.await`, `Future.get`, and the forking combinators (`async`, `parZip`, `parMap`, `raceN`);
- Surefold's own effects: `Logger`, `Metrics`, `ActorRef.tell`, `Hub.publish`;
- writing to a `var` or property captured from outside the lambda;
- `Shared.peek()`.

`@Suppress("SUREFOLD_EFFECT_IN_TRANSACT")` silences one finding, as Kotlin's own warnings are silenced.

## Why this shape

The checker reports known effects (a denylist). **Alternative:** an allowlist that accepts only `Transaction`
calls, the standard library's pure functions and constructors. That is closer to a proof, but it rejects every
call to a user's own pure helper, so people would suppress it everywhere. Recommended: the denylist, with the
families above. Spec 0208 gives each family a legal way to run after commit, so every finding comes with a fix.

## Stack

- [ ] **`spec-0207-checker`** — `TransactChecker` in `surefold-app-compiler`, with I/O, blocking and forks.
      Done when: a test compiling each forbidden call inside `transact` gets the diagnostic, and the same call
      outside it gets none.
- [ ] **`spec-0207-captures`** — captured `var` writes, `peek()`, and functions taking `Transaction`.
      Done when: `count += 1` on a captured `var` is reported, a local `var` declared inside the body is not.
- [ ] **`spec-0207-surefold-effects`** — `Logger`, `Metrics`, `tell` and `publish`, each with a message naming
      0208's after-commit form. A section in `docs/core.md`.
      Done when: the diagnostic for `ref.tell(m)` inside `transact` suggests `afterCommit { ref.tell(m) }`.

## Acceptance

```bash
./gradlew :surefold-app-compiler:check && ./gradlew build
```

## Open questions

1. **Error or warning?** Recommended: error. The bug it prevents is silent and only shows under load.
2. **Same-module helpers.** Should the checker follow a call into a function in the same module and report I/O in
   its body? Recommended: not now. A helper meant for transactions takes `Transaction` and is checked directly.
3. **`synchronized` and `Lock`** inside a body: report them? Recommended: yes. Blocking under contention is the
   failure mode, and the step already gives atomicity.
