# 0199 — A suite that shares nothing

## Problem

Every `lark-junit` suite names a shared resource, even when it shares nothing. Most suites share nothing, so
they write the same two lines and an unused argument on every test:

```kotlin
object ContractSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }
    init { test("name") { _ -> ... } }
}
```

Lark has 204 such suites against about 75 that share something, and petshop has 23. The owner's review of petshop's
move called it a waste.

## Not doing

- **No change to how a suite runs.** Concurrency, aspects, limits, logs and the engine's discovery stay as they
  are.
- **No change to what a test checks.** The tests that move change only their suite's header and `_ ->`.
- **No `Suite<R>` kept as an alias.** Every suite in this repo moves in the same stack, and nothing outside
  this repo and petshop extends it yet.

## Shape

The common case gets the short name. A suite that shares something says so.

```kotlin
object ContractSuite : Suite() {
    init {
        test("a pet is listed") { ... }
    }
}

object OrdersSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()
    init {
        test("an order is published") { kafka -> ... }
    }
}
```

- `SharedSuite<R>` is today's `Suite<R>`, renamed.
- `Suite` takes the same `limit`, `concurrent` and `aspects`, has no `shared`, and its `test` body takes no
  argument.
- Both extend one base the engine finds. Outside `lark-junit`, nothing can extend the base directly.

## Why this shape

Most suites share nothing, so the short name should mean the short form.

**Alternative:** keep `Suite<R>` and add a `Tests` class for the case with nothing shared. That changes no
existing suite, but the name a reader meets first stays the long form. Recommended: rename.

## Stack

- [ ] **`spec-0199-suite`** — `Suite` and `SharedSuite<R>` in `lark-junit`, and every suite in the repo on its
      new header: `Suite<Unit>` with nothing shared becomes `Suite`, every other `Suite<R>` becomes
      `SharedSuite<R>`. One PR, since the repo cannot build with only one half. The move is test code, so the
      PR stays inside the line cap.
      Done when: `./gradlew build` passes with every test reported under its old name.

## Acceptance

```bash
./gradlew build
grep -rn 'Suite<Unit>' --include=*.kt .    # nothing
```

## Open questions

Taken as recommended (maintainer, 2026-10-25: build it).

1. ~~Should `Suite`'s `test` also take a body with an argument?~~ No: two overloads of `test` make `{ ... }`
   ambiguous.
2. ~~What should the shared base be called?~~ `AnySuite`, public with an internal constructor so only
   `lark-junit` extends it.
