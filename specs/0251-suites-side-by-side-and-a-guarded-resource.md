# 0251 — Suites side by side, and a guarded resource

## Problem

The `surefold` engine runs a suite's tests concurrently, but runs the suites themselves one after another
(`SurefoldEngine.execute`: `engine.children.filterIsInstance<SuiteDescriptor>().forEach { run(...) }`). A service
with many small suites, a few tests each, uses a few threads for most of its run. One with 86 suites spends about as
long as the sum of its suites. The only way out today is Gradle's `maxParallelForks`, a JVM per fork, which shares
nothing between forks.

Nothing guards a resource across suites. `SharedSuite<R>` shares one `R` within one suite. When every suite talks to
the same database, nothing says "at most 20 tests use it at once". Run suites side by side and that limit is exactly
what breaks: the database runs out of connections and tests fail with `too many clients`.

## Not doing

- **Parallel JVMs.** Forks stay Gradle's business; this is one JVM's engine.
- **Ordering between suites.** No "run A before B"; a suite that needs another's state is a bug.
- **Changing a suite's own concurrency.** `limit` and `concurrent = false` mean what they mean today, within a suite.
- **A resource that crosses JVMs.** A guard counts permits in one engine run.

## Shape

**Suites run side by side** under one engine-wide limit. It is set by the configuration parameter
`surefold.junit.suites.limit` (default `availableProcessors`), so `1` gives today's behaviour. A suite that must run
alone in the whole run says so: `Suite(alone = true)`.

**A guarded resource** is acquired on first use in the run, released after the last suite, and lent to at most
`permits` tests at once:

```kotlin
// Declared once, as an object: one database for the run, 20 tests on it at a time.
object TestDatabase : Guarded<DataSource>(permits = 20) {
    override val resource: Resource<DataSource> = resource { install({ Postgres.start() }) { db, _ -> db.close() } }
}

object OrdersSuite : SharedSuite<DataSource>() {
    // Each test takes a permit for its whole body, and gives it back when the body ends, passed or failed.
    override val shared: Resource<DataSource> = TestDatabase.lease()
    init {
        test("an order is saved once") { db -> }
    }
}

object InvoicesSuite : Suite() {
    init {
        test("an invoice totals its lines") { TestDatabase.use { db -> } }   // the same guard, from a plain Suite
    }
}
```

A test waiting for a permit is not yet started: its timeout starts with its body, as it does under a suite's
`limit`.

## Why this shape

Running suites side by side is only safe when what they share says how much sharing it takes, so the two land
together. The guard is the engine's, not the caller's semaphore, because only the engine knows when the run ends and
can release the resource then.

**Alternative:** a JUnit-style `@ResourceLock(name)` with read and write modes. It is familiar, but it serialises by
name and cannot express "20 at once". A permit count covers both: `permits = 1` is a lock. Recommended: permits.

## Stack

- [ ] **`spec-0251-guarded`** — `Guarded<R>`, `lease()` and `use { }`; acquired once per engine run, released after
      the last suite.
      Done when: a test of 50 concurrent users of a `Guarded(permits = 3)` never sees more than 3 holders.
- [ ] **`spec-0251-side-by-side`** — suites run concurrently under `surefold.junit.suites.limit`; `alone = true`.
      Done when: two suites each sleeping on a latch finish together, and with the limit at 1 they run in turn.
- [ ] **`spec-0251-docs`** — `docs/testing.md`: a section on running suites side by side, with the database
      example.
      Done when: `DocsSuite` compiles the sample.

## Acceptance

```bash
./gradlew :surefold-junit:test
./gradlew build
```

## Open questions

1. **Default `surefold.junit.suites.limit`.** Recommended: `availableProcessors`. The alternative, 1, keeps today's
   behaviour until a build opts in.
2. **Permits per test or per suite.** Recommended: per test, so a long suite does not hold permits between its tests.
3. **Is a guard's acquisition lazy?** Recommended: yes, on first use, so a run that never touches the database never
   starts it.
4. **Reporting.** Recommended: a test that waited for a permit reports how long, as the timing aspect already does.
