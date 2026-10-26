# 0202 — A story collects its checks

## Problem

A `surefold-junit` story returns the value its block ends with. Each `Then` and `And` returns its own check, so
every step has to be joined into that last value by hand:

```kotlin
val recorded = Then("the registry recorded one new keeper") {
    registry.calls(recordKeeper) shouldBe 1
} and And("the outbox drains") {
    database.unsent() shouldBe 0L
} and And("/stats counts every event") { … }
recorded
```

`} and And(` reads badly, and the value at the end is bookkeeping. Worse, a step left out of it is lost. Its
check runs, and the story marks the step failed in its transcript, but the test passes: `story`'s block is
generic, so the return-value checker can't see the dropped step. petshop's `RegistrySuite` lost a check
that way. Petshop has 78 stories, and surefold has 6.

## Not doing

- **No change to plain tests.** A test without a story still returns its `Expectations`.
- **No change to `surefold-test`'s `story`**, which throws on a failed step.
- **No change to `Given` and `When`**, which hand back a value for later steps.

## Shape

```kotlin
test("the tortoise's adoption is counted once") {
    story {
        Then("the registry recorded one new keeper") { registry.calls(recordKeeper) shouldBe 1 }
        And("the outbox drains") { database.unsent() shouldBe 0L }
        And("/stats counts every event") { shop.call(stats, Unit).events.toLong() shouldBe database.recorded() }
    }
}
```

- `story`'s block returns `Unit`.
- Every step whose value is `Expectations`, of any keyword, is kept. `story` returns them all, joined in step
  order.
- A step's check counts once, even if the test also keeps it in a `val` and uses it.
- The story already reads each step's value to mark it failed (spec 0188). Collecting uses the same hook, in
  `surefold-junit`.

## Why this shape

A story is a list of steps, so its result should be all of them, not whichever the author remembered to join.

**Alternative:** keep the value at the end, and make the return-value checker flag a dropped step. That
catches the loss, but every story still ends in the same bookkeeping. Recommended: collect.

## Stack

- [ ] **`spec-0202-collect`** — `story` collects its steps' checks and its block returns `Unit`, with tests: two
      failing steps both reported, a step kept in a `val` counted once, and a `When` value not counted. The
      repo's 6 stories drop their trailing value, and `docs/testing.md`'s story example is updated.
      Done when: `:surefold-junit:check` passes and the docs example compiles.

## Acceptance

```bash
./gradlew :surefold-junit:check
./gradlew build
```

## Open questions

1. Should a plain test also stop returning a value, and collect every check made in its body? Recommended: not
   in this spec. It's a bigger change to the test model, so spec it separately if this one reads well.
2. Should a step's check made inside `Given` or `When` count too? Recommended: yes. Any step's
   `Expectations` count, whatever its keyword.
