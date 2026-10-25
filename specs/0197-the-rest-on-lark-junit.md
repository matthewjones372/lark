# 0197 — The rest on lark-junit

## Problem

Five modules run their tests on `lark-junit`: `lark-pekko`, `lark-stream-pekko`, `lark-kafka`,
`lark-kafka-pekko` and `lark-actor`. The other 33 modules with tests are on Jupiter: about 900 tests in about
200 classes. Each module that moved so far also copied the same build setup by hand: `lark-junit` on the test
classpath, power-assert for `expect`, and the return-value checker on test compiles.

Specs 0193 and 0196 measured what a move buys. It saves time where tests wait on something slow, as
`lark-kafka` did (−29%), and nothing where each test takes milliseconds, as `lark-actor` showed. Both
recommend moving the rest anyway, for one way to write a test across the repo: shared resources, per-test logs
and checks as values (spec 0174).

## Not doing

- **No move for what `lark-junit` is built on.** `lark`, `lark-assert` and `lark-test` are on `lark-junit`'s
  classpath, so a bug in them would break the runner that reports it. They stay on Jupiter, with `lark-junit`'s
  own tests.
- **No move for the build-tool modules.** `lark-app-gradle` and `lark-app-compiler` build for JDK 21, and
  `lark-junit` is built for 25. The benchmark modules have no tests to move.
- **The usual exceptions stay on Jupiter:**
  - each module's `NoOtherDependenciesTest`;
  - any class that runs the Kotlin compiler (the guide and README example tests);
  - any class that extends a published contract fixture, such as `JournalContract`.
- **No change to what a test checks**, or to any test's name.
- **No timing section per module.** One measurement of the whole `test` run covers them all.

## Shape

- **The build setup moves to the root build.** Every module except those above gets `lark-junit`, power-assert
  and the return-value checker from the root `subprojects` block. The modules already on `lark-junit` drop
  their copies.
- **Each class becomes a suite**, as in spec 0196:
  - an `object FooSuite : Suite<R>()`, with tests named word for word;
  - per-test state, never mutable state in a suite's field;
  - a `Resource` where a class's `@BeforeAll` or `@AfterEach` managed something;
  - `alone`, or `concurrent = false`, only for a test that fails when tests run together, with a comment
    saying why;
  - a parameterized test becomes one test per value, registered in a loop, named with the value in brackets
    at the end, as in spec 0193.
- **`forAll`.** `lark-junit` gets `Iterable<T>.forAll { t -> Expectations }`. It joins each element's checks
  with `and`, and holds on an empty iterable. Spec 0196 found `reduce(Expectations::and)` written by hand
  twice. These modules have about 21 more checks inside `forEach` loops.
- **Tests that need Docker** (the JDBC journal on Postgres, and the cluster tests that use it) run in CI.
  A container without Docker fails them on both runners alike.

## Why this shape

One root setup is less to keep than 33 copies, and it makes `lark-junit` the default for a new module. Moving
module by module, as before, keeps each PR one module's tests, which a reviewer can check against its old
names.

**Alternative:** stop here, and leave the rest on Jupiter. That costs nothing now, but it leaves two ways of
writing a test, and a new module would pick one at random. Recommended: move the rest.

## Stack

- [x] **`spec-0197-setup`** ([#623](https://github.com/matthewjones372/lark/pull/623)) — `forAll`, with tests; the root build's `lark-junit` setup; the modules on
      `lark-junit` drop their copies.
      Done when: `./gradlew build` passes with no module's tests changed.
- [x] **One PR per batch**, each a module or a few small ones, about 8 to 14 classes:
  - `lark-stream`, `lark-stream-test` and `lark-stream-actors` ([#643](https://github.com/matthewjones372/lark/pull/643));
  - `lark-stream-forks` ([#625](https://github.com/matthewjones372/lark/pull/625));
  - `lark-stream-parity` ([#626](https://github.com/matthewjones372/lark/pull/626));
  - `lark-actor-remote` and its three codec modules ([#627](https://github.com/matthewjones372/lark/pull/627));
  - `lark-actor-journal-jdbc` and `lark-actor-projection` ([#631](https://github.com/matthewjones372/lark/pull/631));
  - `lark-cluster`, in two halves ([#629](https://github.com/matthewjones372/lark/pull/629), [#638](https://github.com/matthewjones372/lark/pull/638));
  - `lark-cluster-aws`, `lark-cluster-kubernetes`, `lark-arrow` and `lark-structured` ([#632](https://github.com/matthewjones372/lark/pull/632));
  - `lark-app`, in two halves ([#630](https://github.com/matthewjones372/lark/pull/630), [#634](https://github.com/matthewjones372/lark/pull/634));
  - `lark-app-typesafe` ([#635](https://github.com/matthewjones372/lark/pull/635));
  - `lark-app-actor`, `lark-app-cluster`, `lark-app-kotlinx` and `lark-app-liquibase` ([#637](https://github.com/matthewjones372/lark/pull/637));
  - `lark-app-openfeature`, `lark-app-openfeature-flagd` and `lark-app-pekko` ([#639](https://github.com/matthewjones372/lark/pull/639));
  - `lark-micrometer`, `lark-openfeature`, `lark-otel` and `lark-slf4j` ([#641](https://github.com/matthewjones372/lark/pull/641));
  - `lark-bank` ([#640](https://github.com/matthewjones372/lark/pull/640)).

  Along the way: the matchers the batches found missing ([#633](https://github.com/matthewjones372/lark/pull/633)), a port race in `lark-actor-remote`'s
  tests ([#636](https://github.com/matthewjones372/lark/pull/636)), and a tidy-up of the `expect(...)` stand-ins, stale names and a publisher race ([#642](https://github.com/matthewjones372/lark/pull/642)).

  Done when (each): the moved suites report the same tests, all passing, under `lark`.
- [x] **`spec-0197-result`** — a `## Result` section:
  - the whole `./gradlew test` before and after;
  - each module's test task, from the same profile;
  - every `alone` or `concurrent = false` added, and why;
  - what stays on Jupiter.

  Done when: the section holds measured numbers.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -rlE '@(Test|ParameterizedTest)\b' --include=*.kt */*/src/test   # only the classes in Not doing
```

## Result

Measured on 2026-10-25, on a 4-core container with no Docker, as `./gradlew cleanTest test --continue
--no-build-cache --profile`. That is every module's tests, with Gradle running modules in parallel as it
does by default. Each side had one warm-up, then 3 runs, back to back: before on `837bcadf` (#619, the
spec), after on `main` with #643 and this PR. The Docker tests (Postgres, Kafka; 66 before, 68 after) fail
fast on both sides, so their modules' times here are without them. Medians:

| | Before | After |
|---|---|---|
| Whole command | 246.7 s | 249.0 s (+1%) |
| Sum of every module's test task | 945.9 s | 924.8 s (−2%) |
| Tests on `lark` / on Jupiter | 498 / 1,884 | 1,796 / 598 |

Whole command, runs: 250.2, 246.6, 242.6 s before; 251.9, 248.7, 249.0 s after. No test failed on either side
but the Docker ones.

The modules this spec moved, with any test task over 5 s on either side:

| Test task | Before | After |
|---|---|---|
| `:lark-cluster` | 148.9 s | 131.9 s (−11%) |
| `:lark-app-cluster` | 60.9 s | 55.5 s (−9%) |
| `:lark-stream-parity` | 52.6 s | 42.9 s (−18%) |
| `:lark-cluster-kubernetes` | 18.4 s | 12.6 s (−32%) |
| `:lark-actor-remote` | 11.7 s | 10.6 s (−10%) |
| `:lark-stream-forks` | 10.8 s | 9.0 s (−16%) |
| `:lark-app-typesafe` | 10.2 s | 7.7 s (−24%) |
| `:lark-app` | 9.0 s | 7.8 s (−14%) |
| `:lark-bank` | 7.4 s | 10.9 s (+48%) |
| `:lark-structured` | 5.3 s | 6.6 s (+26%) |

- **The build is no faster.** The whole command is the longest chain of modules, not their sum. That chain is
  `lark-cluster`, then the Gradle plugin's TestKit builds, still on Jupiter, at 105 s. Modules already on
  `lark` before this spec, such as `lark-kafka` and `lark-actor`, moved by up to 7% between the sides, which
  is the noise here. Most small modules' changes are inside it.
- **Where it helped:** modules whose suites hold several slow tests, as specs 0193 and 0196 found:
  `lark-cluster`, `lark-stream-parity`, `lark-cluster-kubernetes`, `lark-app-typesafe`. A suite of one test,
  which many cluster classes are, gains nothing, because suites run one after another.
- **Where it cost:** `lark-bank` (+3.6 s), not explained. Most of its tests need Docker, so here its time is
  mostly how long they take to fail, and it started its nodes once per class before as it does once per suite
  now. A few small modules, such as `lark-structured`, are slower by about a second each, within the noise.
- **`alone` and `concurrent = false`**, each a test that touches process-wide state:
  - `PostgresFeedGapSuite`, `concurrent = false`: a gap is read from `pg_current_snapshot()`, whose horizon
    spans every database on the server, and its tests hold transactions open. It failed on CI the first time
    its tests ran together.
  - `LoadedLayersSuite`, three tests `alone`: they set system properties and clear Typesafe Config's caches.
  - `SectionReportSuite`, `concurrent = false`: its tests read a handler on the shared `lark-app` logger.
  - `OpenFeatureSuite`, `DeclaredFlagsSuite` and `FlagdFlagsSuite`, `concurrent = false`: each test installs a
    provider into the process-wide `OpenFeatureAPI`.
  - `Slf4jLoggerSuite`, `concurrent = false`: its tests read what reached one logback logger.
- **Races the move found, fixed rather than made `alone`:**
  - **Ports** ([#636](https://github.com/matthewjones372/lark/pull/636)): a port picked with `ServerSocket(0)` and
    bound later could be taken by another test's outgoing connection. `freePort()`, in `lark-actor-remote`'s
    test fixtures, picks below the ephemeral range.
  - **A publisher's shutdown** ([#642](https://github.com/matthewjones372/lark/pull/642)): `PublisherSuite`'s
    test publisher could refuse a last request with `RejectedExecutionException`.
  - **Shared fields:** a JVM-wide tracker in `ShardingSuite`, and mutable fields in about thirty classes, each
    made per test.
  - **SLF4J's start:** a first call from several threads at once got SLF4J's stand-in factory; the logger
    context is now the suite's resource.
- **Checks that never counted on Jupiter**, made on another thread or in a callback, now hand their result back:
  two in `lark-stream-forks`, one in `lark-actor-journal-jdbc` and two in `lark-app-cluster`.
- **What stays on Jupiter** (598 tests):
  - `lark`, `lark-assert`, `lark-test` and `lark-junit`'s own tests, which `lark-junit` runs on.
  - `lark-app-gradle` and `lark-app-compiler`, built for JDK 21.
  - Each module's `NoOtherDependenciesTest`.
  - The classes that run the compiler: `ActorsGuideTest`, `GuideExampleTest`, `AskTest`, `CoreGuideTest`,
    `CookbookTest`, `CommittedCompileTest` and `DoesNotCompileTest`.
  - `JdbcJournalTest` and `lark-actor`'s `InMemoryJournalTest` and `InMemorySnapshotsTest`, which extend a
    published contract. Also `ThreadsOnTimeoutTest`.
  - `lark-stream-pekko`'s `BackendTest` and `FunctionalStyleTest`, kept there by spec 0191.
  - `lark-stream-actors`' `ActorsTest`. One of its tests keeps every virtual-thread carrier busy on purpose,
    and a suite's test body runs on a virtual thread, so the body never got back to stop the runs.
- **Gaps found in `lark-junit`**, closed along the way:
  - The string matchers, `shouldContainOnly`, a map's `shouldContainExactly` and a vararg `shouldContainAll`
    ([#633](https://github.com/matthewjones372/lark/pull/633)).
  - A lazy `withClue({ … })` ([#642](https://github.com/matthewjones372/lark/pull/642)).
  - Still open:
    - no supported way to read a `LarkLocal`'s value with nothing bound, from inside a suite. The engine binds
      its captured logs and metrics around each test, so `lark-micrometer` and `lark-slf4j` read the classpath
      default on a fresh thread;
    - no matcher for Playwright's waiting assertions, which `lark-bank`'s page tests still call.
- **What next:** nothing to move. If the build's time matters, the Gradle plugin's TestKit builds and
  `lark-cluster`'s one-test suites are the long chain. Running suites concurrently across a module, not only
  tests within a suite, is the change that would shorten the second; it would want its own spec.

## Open questions

Taken as recommended (maintainer, 2026-10-25: migrate the rest and merge it).

1. ~~Move `lark`, `lark-assert` and `lark-test` too?~~ No: `lark-junit` runs on them.
2. ~~One spec, or one per module?~~ One. Each module is one batch PR, and the result measures them together.
