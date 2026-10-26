# 0135 — A service that does not say "wait"

## Problem

Every service's `SurefoldApp` ends the same way: `run` parks until the process is
asked to stop, which is `awaitShutdown()`. It is boilerplate in every app, and
the version a reader guesses instead, parking on the root's own wait such as a
server's `block()`, is the bug spec 0132 had to warn about: that wait ends only
on the graph's release, which runs after `run` returns, so a SIGTERM releases
nothing until the grace period kills the process. Petshop had exactly that.

## Not doing

- No change to `runApp`, the exit code or the shutdown hook.
- No second application type. A job that does its work and returns keeps
  overriding `run`, as today.
- No removal of `AppScope` or `awaitShutdown`; they stay for a `run` that does
  something first.

## Shape

`run` is `open`, and its default parks until the process is asked to stop:

```kotlin
abstract class SurefoldApp<A : Any> {
    abstract val module: Module
    open fun AppScope.run(root: A) = awaitShutdown()
}

object Petshop : SurefoldApp<PelicanServer>() {
    override val module: Module = petshop
}

object Reindex : SurefoldApp<Indexer>() {          // a job still says what it does
    override val module = indexing
    override fun AppScope.run(root: Indexer) = root.reindexEverything()
}
```

A line a service logged in `run` once it was up moves to the node that owns it,
as an `onStarted` hook, which runs once every node is built.

## Why this shape

A default rather than a second class (`SurefoldService` beside `SurefoldApp`): one name
to learn, and a job is the exception that overrides. Source-compatible: every
existing `run` still compiles and means what it did. `SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN`
stays, for a `run` that is overridden and parks on the wrong thing.

## Stack

- [x] **`spec-0135-run-default`** ([#393](https://github.com/matthewjones372/lark/pull/393)): `run` open with `awaitShutdown()` as its
      default, `SurefoldApp`'s KDoc, and a test that a `SurefoldApp` without `run`
      starts, parks, and releases its graph when `Shutdown.request()` is called.
      Done when: `./gradlew :surefold-app:build` passes with the new test.
- [x] **`spec-0135-docs`** ([#394](https://github.com/matthewjones372/lark/pull/394)): the README, `docs/app.md` and the cookbook's "Run
      it" drop the `run` that only waits.
      Done when: `CookbookTest` compiles every changed recipe.

## Acceptance

```bash
./gradlew :surefold-app:build :surefold-app-pekko:test
```

## Open questions

1. **Should `runApp` log that the app is up and waiting?** Recommend: no; the
   node that knows what "up" means says so, in an `onStarted` hook.
2. **A `fun main() = surefoldMain(App)` that also exits?** Recommend: no; the exit
   code is `main`'s one decision, and `exitProcess(runApp(App).code)` is already
   one line.
