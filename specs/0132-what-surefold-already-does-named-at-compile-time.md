# 0132 — What surefold already does, named at compile time

## Problem

A service on surefold can hand-roll something surefold ships, and nothing says so.
Petshop had four at once, all written by an agent that did not know the surefold
API existed:

- `AppScope.run` parked on `server.block()`, which waits for the graph's own
  release, so a SIGTERM sat out the 30s grace period and released nothing.
- A `ScheduledExecutorService` in a node polled checks into an
  `AtomicReference`, where `Stream.tick` on the graph's `StreamBackend` is the
  shape every other background job had.
- A spec waited with `Thread.sleep(200)` instead of `eventually`.
- `string("password")` in a `config { }` block, so the data class printed it.

The checker already runs inside every compile of a project with the wiring
plugin, and a warning there reaches the editor, the build log and any agent
reading it. It only checks graphs today.

## Not doing

- No errors. Each is a warning; `allWarningsAsErrors` is the user's choice.
- No general lint (detekt rules, style). Only "surefold has this already".
- No rule that needs to follow a value across files or calls. Each rule
  matches one call where it is written.
- No auto-fix or quick-fix in the IDE.

## Shape

Four diagnostics from a `FirFunctionCallChecker` beside `ApplicationChecker`,
each naming the surefold call to use:

```kotlin
override fun AppScope.run(root: PelicanServer) {
    root.block()      // SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN: use awaitShutdown()
}

Executors.newSingleThreadScheduledExecutor()   // SUREFOLD_SCHEDULER_BY_HAND: Stream.tick on the StreamBackend
Thread.sleep(200)                              // SUREFOLD_SLEEP_IN_TEST (test compilations only): eventually(within)
config<Db>("db") { Db(string("password")) }    // SUREFOLD_SECRET_AS_STRING: secret("password")
```

| Diagnostic | Matches | Where |
|---|---|---|
| `SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN` | a `run` override of `SurefoldApp` whose body never calls `awaitShutdown` and calls a zero-argument `block`, `join` or `await` | main |
| `SUREFOLD_SCHEDULER_BY_HAND` | `Executors.new*Scheduled*`, `ScheduledThreadPoolExecutor(..)`, `Timer(..)` | main |
| `SUREFOLD_SLEEP_IN_TEST` | `Thread.sleep`, `TimeUnit.*.sleep` | test |
| `SUREFOLD_SECRET_AS_STRING` | `Reading.string(path)` with a literal `path` whose last segment is in `Secrets.default`'s names | main (question 3) |

Each is suppressible by name, `@Suppress("SUREFOLD_SLEEP_IN_TEST")`, for the case
that means it. The Gradle plugin tells the checker whether a compilation is a
test one, as a second `SubpluginOption` beside `verbose`.

`SurefoldApp`'s KDoc example, which shows `root.block()`, changes to
`awaitShutdown()` in the first entry.

## Why this shape

A call checker, not a Gradle task: the compiler is the one place that runs on
every build and in the editor, and it has resolved symbols, so `Thread.sleep`
is matched by its FQ name rather than by text. The alternative, an `llms.txt`
or a skill, tells an agent what exists but does not stop a build that ignores
it; recommend both, this spec doing the build half. The secret names are
copied from `Secrets.default` into the checker, because the checker cannot
depend on `surefold-app-typesafe`; a test fails if the two lists drift.

## Stack

- [x] **`spec-0132-call-checker`** ([#376](https://github.com/matthewjones372/lark/pull/376)): the call checker, the test-compilation
      option, `SUREFOLD_SLEEP_IN_TEST`, and the `SurefoldApp` KDoc fix.
      Done when: a TestKit project warns on `Thread.sleep` in `src/test` and not
      in `src/main`, and `@Suppress` silences it.
- [x] **`spec-0132-scheduler`** ([#377](https://github.com/matthewjones372/lark/pull/377)): `SUREFOLD_SCHEDULER_BY_HAND`.
      Done when: each matched constructor warns in a TestKit project; a plain
      `Executors.newVirtualThreadPerTaskExecutor()` does not.
- [x] **`spec-0132-secret`** ([#378](https://github.com/matthewjones372/lark/pull/378)): `SUREFOLD_SECRET_AS_STRING`, and the drift test
      against `Secrets.default`.
      Done when: `string("password")` warns, `string("passwordPolicy")` and a
      non-literal path do not.
- [x] **`spec-0132-run`** ([#379](https://github.com/matthewjones372/lark/pull/379)): `SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN`, and a cookbook
      section listing all four.
      Done when: `run { root.block() }` warns, `run { awaitShutdown() }` and a
      `run` that returns do not.

## Acceptance

```bash
./gradlew :surefold-app-compiler:build :surefold-app-gradle:test
```

## Open questions

Answered:

1. **`run` without `awaitShutdown`** warns only when it also calls a
   zero-argument `block`, `join` or `await`. A `run` that returns is silent.
2. **`@Suppress` by name** is proved in the first entry's TestKit test. If K2
   does not honour it for a plugin diagnostic, that entry adds a
   `surefold { warnings { disable(...) } }` list instead, and says so in its PR.
3. **`SUREFOLD_SECRET_AS_STRING`** is main compilations only.
4. **More patterns** (`runCatching` around what `policy` covers, a hand-written
   retry loop) are a later spec.
