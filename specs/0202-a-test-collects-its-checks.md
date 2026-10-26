# 0202 — A test collects its checks

## Problem

A `surefold-junit` test returns its checks as one value, so every check has to be joined into it with `and`:

```kotlin
(app.request(getPet, 1L).toString() shouldBe "GET /pets/1") and
    (app.request(adoptPet, 1L).toString() shouldBe "POST /pets/1/adoption")
```

Stories do the same with `} and And(…)`. The joins carry no meaning, they need brackets because infix calls
group left, and a check that is left out is lost. A story step left out of the value still passes the test,
because `story`'s block is generic and the return-value checker can't see it: petshop's `RegistrySuite` lost a
check that way. Surefold's suites join checks 884 times, in 268 files. The owner's review of petshop: this
makes the tests harder to read.

## Not doing

- **No change to how a failure reads**, or to a failed check not stopping the test: every failure is still reported.
- **No change to the narrowing checks.** `shouldNotBeNull { }`, `shouldBeInstanceOf<T> { }`,
  `shouldBeRight { }`, `forAll`, `holds` and `withClue` keep their shapes; their blocks return `Unit`.
- **No change to `surefold-test` or `surefold-assert`.**

## Shape

```kotlin
test("adopting twice answers the failure the endpoint declared") {
    val app = app()
    app.outcome(adoptPet, 1L).shouldBeOk() shouldBe nibbles.copy(standing = Adopted("Ada")).toDto()
    app.outcome(adoptPet, 1L).shouldBeError() shouldBe ProblemDto.AlreadyAdopted(1, "Pet 1 is already adopted")
}

test("the tortoise's adoption is counted once") {
    story {
        Then("the registry recorded one new keeper") { registry.calls(recordKeeper) shouldBe 1 }
        And("/stats counts every event") { shop.call(stats, Unit).events.toLong() shouldBe database.recorded() }
    }
}
```

- **Collecting.** The engine binds a record of checks around each test, as it binds the test's logs. Each
  check made while the test runs adds to it, on a fork or an actor too, wherever surefold carries the test's
  context. The test's result is everything recorded, in the order it was checked.
- **Bodies return `Unit`:** a test's, a story's and a step's.
- **`or` and `!`** take blocks, `anyOf({ … }, { … })` and `not { … }`, whose checks count only through them.
- **A test that records no check fails**, saying so. Today the compiler rejects it.
- A body that still ends in `a and b` compiles and counts each check once, so suites can move one at a time.

## Why this shape

A test is a list of things that should be true. Writing them as statements reads as that list.

**Alternative:** keep returning the value, and collect only story steps. That fixes `and And`, but leaves 884
joins and the bracket trap in plain tests. Recommended: collect everywhere.

## Stack

- [ ] **`spec-0202-record`** — the record, bodies returning `Unit`, `story` collecting, `anyOf` and `not`, the
      empty-test failure, tests, and `docs/testing.md`. Old `a and b` bodies still pass.
      Done when: `:surefold-junit:check` and `./gradlew build` pass with no suite changed.
- [ ] **`spec-0202-unjoin`** — surefold's suites drop their joins and trailing values. This is test code only,
      split into PRs by module group, as spec 0197 was.
      Done when: `grep -rn ') and$' --include=*Suite.kt .` finds nothing.
- [ ] **`spec-0202-retire`** — `and`, `or` and `!` on `Expectations` are removed, and checks return `Unit`.
      Done when: `./gradlew build` passes.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. A check made on a thread surefold doesn't carry, such as a bare `Thread`, has no record to add to. Should it
   throw there? Recommended: yes, an `IllegalStateException` naming the check. A check lost silently is the
   bug this spec removes.
2. Should the empty-test failure stay, given that a test which only expects no exception would need
   `holds { … }`? Recommended: yes.
3. Should `Expectations` stay public once checks return `Unit`? Recommended: no, internal after
   `spec-0202-retire`.
