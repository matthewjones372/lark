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

- [ ] **`spec-0197-setup`** — `forAll`, with tests; the root build's `lark-junit` setup; the modules on
      `lark-junit` drop their copies.
      Done when: `./gradlew build` passes with no module's tests changed.
- [ ] **One PR per batch**, each a module or a few small ones, about 8 to 14 classes:
  - `lark-stream`, `lark-stream-test` and `lark-stream-actors`;
  - `lark-stream-forks`;
  - `lark-stream-parity`;
  - `lark-actor-remote` and its three codec modules;
  - `lark-actor-journal-jdbc` and `lark-actor-projection`;
  - `lark-cluster`, in two halves;
  - `lark-cluster-aws`, `lark-cluster-kubernetes`, `lark-arrow` and `lark-structured`;
  - `lark-app`, in two halves;
  - `lark-app-typesafe`;
  - `lark-app-actor`, `lark-app-cluster`, `lark-app-kotlinx` and `lark-app-liquibase`;
  - `lark-app-openfeature`, `lark-app-openfeature-flagd` and `lark-app-pekko`;
  - `lark-micrometer`, `lark-openfeature`, `lark-otel` and `lark-slf4j`;
  - `lark-bank`.

  Done when (each): the moved suites report the same tests, all passing, under `lark`.
- [ ] **`spec-0197-result`** — a `## Result` section:
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

## Open questions

Taken as recommended (maintainer, 2026-10-25: migrate the rest and merge it).

1. ~~Move `lark`, `lark-assert` and `lark-test` too?~~ No: `lark-junit` runs on them.
2. ~~One spec, or one per module?~~ One. Each module is one batch PR, and the result measures them together.
