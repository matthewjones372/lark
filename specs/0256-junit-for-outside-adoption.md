# 0256 — A JUnit module another team can adopt

## Problem

A Kotlin team evaluating `surefold-junit` must find it inside a much wider library,
trust experimental APIs across hundreds of tests, and establish tooling compatibility
itself. The execution, resources and diagnostics are useful; their adoption cost is unclear.
The README also promises compile-time rejection of tests with no checks, while
`RecordedTest` establishes a runtime failure. This weakens trust in other guarantees.

Draft for review, 2026-10-29. Based on Surefold main and released 0.15.0 sources.

## Not doing

New matchers, a new test DSL, a separate runtime, production-library migration,
Jupiter extension emulation, paid features, pricing or outreach. No claim that shared
mutable data becomes isolated, that arbitrary application threads inherit context,
or that a virtual thread makes uninterruptible work cancellable.

## Shape

1. A dedicated JUnit guide, linked from the README and Surefold site, opens with:
   "Concurrent Kotlin integration tests, managed fixtures, and logs attached to failures."
   One complete example shows success, failed checks and the resulting diagnostic output.
   Released and unreleased behaviour are distinct; examples compile against their named release.
2. A standalone starter uses published artifacts and a checked-in Gradle wrapper, with
   one Jupiter test and one Surefold suite in one task. It needs no production Surefold
   dependency, source checkout or Maven Local. A fresh clone runs from its documented directory.
   It demonstrates a managed Testcontainers fixture, per-test data isolation, cleanup,
   single-test selection and rerunning a failure. Docker is an explicit prerequisite.
3. Migration guidance states how existing assertions behave, including a successful
   third-party assertion with no recorded Surefold checks. It explains collected failures,
   fail-fast exceptions, timeouts, retries, fixture ownership and context boundaries.
   It names unsupported Jupiter extensions and when to retain a Jupiter test.
4. A comparison runs the same blocking integration workload in both engines, with equal
   fixture reuse, concurrency limits, assertions and logging. It reports startup separately,
   warm-ups, repeated timings, environment and raw results; no required speedup or invented numbers.
5. A versioned support table distinguishes automated checks, manual checks and unsupported
   combinations of JDK, Kotlin, JUnit Platform, Gradle and IntelliJ. Existing engine tests
   are reused; gaps cover filtering, reruns, debugger navigation, XML and mixed engines.
   Acquisition failure, teardown failure and cancellation report honestly and release fixtures.
6. A stability policy names the supported public API across junit, its exposed core/test
   types and testkit. Compatibility checks and migration notes back that promise; no
   declaration of stability while exposed dependencies can silently break consumers.
7. A pilot checklist records adoption time, blockers, changes needed and continued use.
   Three independently maintained teams outside the author's projects are the adoption
   milestone. Participation and permission to publish results are voluntary; recruitment
   and uncollected results never become an automated build or release requirement.

## Why this shape

Recommend a focused module within Surefold: its runtime and Resource semantics are the
advantage, and one-suite adoption keeps evaluation small. A separate repository adds
release work without removing those dependencies. Adding features before observing
external use expands the maintenance promise without evidence of what users need.

## Stack

Each entry is a separate PR, near the repository's 200-line soft cap; split before exceeding it.

- [ ] **`codex/junit-claims`** — correct claims and comparisons; done when each guarantee cites its evidence.
- [ ] **`codex/junit-starter`** — mixed-engine starter; done when published dependencies run from a fresh copy.
- [ ] **`codex/junit-migration`** — fixture example and migration guide; done when the documented scenarios run.
- [ ] **`codex/junit-comparison`** — fair runner and raw results; done when another machine can reproduce the method.
- [ ] **`codex/junit-compatibility`** — support table and missing tooling checks; done when each supported row has evidence.
- [ ] **`codex/junit-stability`** — approved API policy and checks; done when an incompatible fixture change fails the check.
- [ ] **`codex/junit-front-door`** — guide, sample output and pilot checklist; done when all links reach released examples.

## Acceptance

In the current Surefold checkout, formatting and the full build pass. In a fresh copy
of `examples/junit-adoption`, the following proposed starter commands pass with Docker:

```bash
./gradlew test
./gradlew test --tests '*InventorySuite*'
./gradlew compareSuites
```

The test task reports both engines; an intentionally failing example exits nonzero,
shows only its own logs and releases its fixture. Restoring it passes. The guide gives
exact CLI and IDE steps for selecting one case, rerunning it and debugging it.
Timing results are observations; correctness and compatibility claims are checked gates.

## Open questions

1. Support range? Recommend JDK 25 and the release's Kotlin/JUnit versions initially;
   publish exact Gradle and IntelliJ versions verified, then widen only with evidence.
2. Deprecation window? Recommend at least two minor releases and 90 days, whichever
   is longer, with migration notes; approve the policy before promising stability.
3. First fixture? Recommend one Postgres Testcontainer shared by each engine's suite,
   with independent data per test; SQLite or a fake lowers setup cost but proves less integration value.
4. Third-party-only assertions? Recommend document today's no-recorded-check failure
   and keep Jupiter available; any opt-out or assertion bridge needs a separate approved spec.
