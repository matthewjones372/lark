# 0244 — A test for each case

## Problem

A suite that runs one check over many inputs loops in `init` and builds each name by hand, as `SurefoldSchemaSuite`
does over `SurefoldSchema.entries`. It works, but the cases land flat among the suite's other tests, nothing groups
them in IntelliJ's tree or the JUnit XML, two cases that render alike fail registration with "already has a test
named", and every author invents their own name format. Jupiter users reach for `@ParameterizedTest` and find no
counterpart.

## Not doing

- No annotations, no `@MethodSource`-style lookup by name, no CSV parsing: cases are Kotlin values.
- No generated inputs or shrinking. That is property testing, a separate spec if wanted.
- No per-case aspects. The group takes aspects once, and they apply to every case.
- No cartesian helpers (`cases × cases`); `flatMap` already does it.

## Shape

`testEach` on `Suite` and `SharedSuite`, beside `test`. It registers one test per case, named by `named`, under a
container named by the group.

<!-- sketch -->
```kotlin
data class Case(val text: String, val expected: Duration)

object DurationSuite : Suite() {
    init {
        testEach(
            "a duration parses",
            listOf(Case("1s", 1.seconds), Case("2m", 2.minutes), Case("1h30m", 90.minutes)),
            named = { it.text },
        ) { case ->
            expect(Duration.parse(case.text) == case.expected)
        }
    }
}

object PetShopSuite : SharedSuite<PetShop>() {
    init {
        testEach("a listed pet is available", listOf("Nibbles", "Mrs Peel"), aspects = tag("shop")) { shop, pet ->
            shop.list(pet)
            expect(shop.status(pet) == "available")
        }
    }
}
```

- Signature: `testEach(name: String, cases: Iterable<T>, aspects: Aspect = Aspect.none, named: (T) -> String =
  { it.toString() }, body: (T) -> Unit)`; the `SharedSuite` body is `(R, T) -> Unit`.
- Reported as a container `a duration parses` with tests `1s`, `2m`, `1h30m`. Unique ID
  `[suite:…]/[group:a duration parses]/[test:1s]`, so an IDE reruns the group or one case.
- Each case is an ordinary registered test: its own fork, its own logs, its own checks, the group's aspects inside the
  suite's.
- Two cases with the same name fail registration, naming the group and the name, as two tests do today.
- An empty `cases` registers an empty container, which the platform reports as passing with no tests.
- Two groups with the same name fail registration too, as the group's name is its ID (added in build).
- A case's failure message, story title and printed logs name it `group [case]`, so a failure says which group
  it is in (added in build).

## Why this shape

A function beside `test` keeps a suite one `init` block of plain Kotlin; the cases are whatever list the author has.
**Alternative A:** no new API, only flat names via a helper that formats `"$group [$case]"` — a few lines, but nothing
groups in the tree and rerunning one case still means a long ID. **Alternative B:** a nested container in the engine
(recommended) — the engine already builds descriptors by hand, so a `GroupDescriptor` between suite and test is a
small change, and it is what users of `@ParameterizedTest` expect to see.

## Stack

- [x] **`spec-0244-test-each`** ([#877](https://github.com/matthewjones372/surefold/pull/877)) — `testEach` on `Suite` and `SharedSuite`, a `GroupDescriptor` container, and
      the unique-ID resolver taking `[group:…]`.
      Done when: a sample suite's three cases report under one container in `EngineTestKit` events; one failing case
      fails alone; selecting `…/[group:g]/[test:2m]` runs only that case; a duplicate case name fails registration.
- [x] **`spec-0244-docs`** ([#878](https://github.com/matthewjones372/surefold/pull/878)) — `docs/testing.md` gains "A test for each case" with a compiled fence
      (`<!-- testing-junit-each -->`), and `SurefoldSchemaSuite` moves to `testEach`.
      Done when: the guide's fence compiles under `CoreGuideTest`.

## Acceptance

```bash
./gradlew :surefold-junit:test :surefold-app-flyway:test
```

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Name:** `testEach`, `each`, or an overload `test(name, cases) { … }`? **Answered: `testEach`** (maintainer, 2026-10-28). Recommend `testEach`: it reads as what it
   does, and an overload of `test` taking a list is easy to misread at the call site.
2. **Default case name:** `toString()`, or `"[1] toString()"` with the index as Jupiter does? **Answered: `toString()`** (maintainer, 2026-10-28). Recommend plain
   `toString()`; an index makes names shift when a case is inserted, which breaks rerun-by-ID.
3. **Do the group's cases run concurrently with each other** like any tests, or one after another? **Answered: concurrently** (maintainer,
   2026-10-28). Recommend concurrently, as everything else in a suite; `aspects = alone` already gives the other.
4. **Two-row shorthand:** add `testEach(name, vararg pairs: Pair<A, B>) { a, b -> }`? **Deferred** (maintainer, 2026-10-28). Recommend deferred: data
   classes and destructuring cover it, and it doubles the overloads.
