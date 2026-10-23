# 0180 — Stories in suites

## Problem

`story { Given / When / Then }` (spec 0115) runs inside a `lark-junit` test (spec 0174), but it fits
badly. Checked on `main` (2026-10-23):

- **Title.** It defaults to the calling method, which inside a suite is `_init_$lambda$1`. Every
  story in a suite needs a title written out by hand.
- **Transcript.** It is printed to stdout on every run, passing or failing. It bypasses the per-test
  log capture, so the stories of concurrent tests interleave, and a passing test is noisy.
- **Result.** A test body must return `Expectations` (spec 0175), so a story needs a trailing
  `expect(true)`. A step's check throws, so only the first failure in a story is reported.

The maintainer decided on 2026-10-23 to revisit `lark-test`'s promise that it doesn't choose an
assertion library. This spec is that revisit.

## Not doing

- **No change to `story` under Jupiter.** Outside a suite, its title, transcript and throwing steps
  stay as they are.
- **No new step keywords, and no change to `eventually`.**
- **No moving `story` into `lark-junit`.**

## Shape

```kotlin
test("an order is saved once") { db ->
    story {                                               // titled "an order is saved once"
        Given("an empty database") { db.clear() }
        When("the order is saved twice") { repeat(2) { db.save(order) } }
        Then("it is stored once") { db.count() shouldBe 1 } and
            Then("it can be found") { db.find(order.id) shouldBeRight order }
    }
}
```

- **Title:** `story`'s default title is the test's name when it runs inside a suite. `lark-test` reads
  it from a `LarkLocal`, which `lark-junit` binds for each test.
- **Transcript:** inside a suite, it goes through the test's captured logger. It appears with the
  test's other lines, only if the test fails.
- **Result:** `lark-junit` adds a `story` overload whose block returns `Expectations` and which
  returns them. Steps are generic, so `Then("…") { a shouldBe b }` is already an `Expectations`, and
  steps combine with `and` like any check. A step that throws still ends the story as it does today.

## Why this shape

There are two ways to point the dependency:

- **`lark-junit` depends on `lark-test` (recommended).** `lark-test` gains two `LarkLocal`s (the
  title, and where the transcript goes) and keeps depending only on `lark`. Jupiter users get nothing
  new on their classpath.
- **`lark-test` depends on `lark-junit`,** so that `story` itself returns `Expectations`. That is
  closer to the 2026-10-23 wording, "lark-test takes lark-assert". But every `story` user would pull
  in the JUnit Platform engine.

Binding through `LarkLocal`s is how the clock, logger and metrics already reach a test (spec 0174).
So a fork the story opens sees the same title and capture.

## Stack

- [ ] **`spec-0180-story-locals`** — `lark-test`: the story's title and transcript sink become
      `LarkLocal`s whose defaults behave exactly as today.
      Done when: the existing `story` tests pass unchanged, and a test that binds both sees its title
      and transcript go where it bound them.
- [ ] **`spec-0180-story-in-suites`** — `lark-junit` depends on `lark-test`, binds both locals for each
      test, and adds the `story` overload that returns `Expectations`.
      Done when, inside a suite: an untitled story is titled with its test's name; a passing story
      prints nothing; a failing story's transcript is in the test's `logs` entry; two failing
      `Then`s joined with `and` are both reported.
- [ ] **`spec-0180-docs`** — `docs/testing.md`: stories in a suite, with a compiled
      `<!-- testing-junit-story -->` example.
      Done when: `CoreGuideTest` compiles it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Which way does the dependency point?** Recommend `lark-junit → lark-test`, for the classpath
   reason above. This narrows the 2026-10-23 "lark-test takes lark-assert" decision, so please
   confirm it.
2. **Should a dropped `Then(...)` be a compile error?** Steps are generic, and the return-value checker
   only flags functions marked must-use. So `Then("x") { a shouldBe b }` on a line of its own is
   silently lost. Recommend marking the steps must-use only when their block returns `Expectations`.
   Kotlin can't express that on a generic, so `lark-junit` would need its own `Then` overload taking
   `() -> Expectations`. The first code entry checks whether that resolves cleanly.
3. **Should a step's expectations be shown in the transcript?** For example, `✗ Then it is stored once`
   could appear even though nothing threw. Recommend yes: the step is marked failed when its
   `Expectations` failed, so the transcript and the report agree.
4. **Outside a suite, should the transcript still go to stdout?** Recommend yes, unchanged. Jupiter
   tests have no per-test capture to send it to.
