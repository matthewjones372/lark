# 0201 — A wait for a check

## Problem

`eventually(within) { }` from `surefold-test` tries its block again only when the block throws. A
`surefold-junit` check never throws: it returns its `Expectations`. So a wait for a check does not wait:

```kotlin
eventually(5.seconds) { shop.find(nibbles)?.standing shouldBe Adopted("Ada") }   // one try, then fails
Then("the projection counts it").eventually(5.seconds) { tally().events shouldBe 1 }  // the same, as a step
```

It compiles and fails at once whenever the check isn't true yet. So tests wait some other way:
- petshop has two helpers, used 38 times:
  - `shouldBeOrRetry` throws so that `eventually` retries;
  - `waitUntil` polls a value, then a normal check follows.
- 9 of surefold's own suites hand-roll 11 deadline loops on `System.nanoTime()`, such as `UnorderedSuite.waitFor`.

## Not doing

- **No change to `eventually`'s clock, spacing or `GaveUp`** for a block that returns anything else.
- **No new wait for values.** A block that returns a plain value is waited on as today, until it stops throwing.
- **No timeouts on suites.** That is the `timeout` aspect's job.

## Shape

`eventually` waits until its block's checks hold, at the top level or as a story step:

```kotlin
eventually(5.seconds) { shop.find(nibbles)?.standing shouldBe Adopted("Ada") }

Then("the consumer sees the second as a duplicate").eventually(5.seconds) { tally().duplicates shouldBe 1 }
```

- **What counts as not yet:** a try that returns failed `Expectations` is not yet, as a try that throws is today.
  The block runs again every `every`.
- **Giving up:** the last try's `Expectations` are returned as they are, each failure named where it was
  checked, with a first line saying how many tries in how long. Nothing is thrown, so other checks in the
  test still report.
- **How:** `surefold-test` defines a small interface, a result that can say it did not hold and can mark itself
  as given up. `eventually` and `Story.Waiting.eventually` read it, and `Expectations` implements it.
  `surefold-test` still does not depend on `surefold-junit`.

## Why this shape

Every existing `eventually` around a check starts waiting, with no new name to learn and no import to get
wrong. That includes the steps of a story, whose `eventually` is a member that no extension can override.

**Alternative:** a separate `eventually` in `surefold-junit`, chosen by import, as `shouldBe` is. That keeps
`surefold-test` unaware of checks, but a file importing the other one compiles and still doesn't wait, and
story steps would need a third name. Recommended: the interface.

## Stack

- [ ] **`spec-0201-wait`** — the interface and `eventually` reading it in `surefold-test`, `Expectations`
      implementing it, tests for each case, and a paragraph in `docs/testing.md`.
      Done when: `:surefold-test:check` and `:surefold-junit:check` pass.
      The tests cover a check that holds on the third try, one that never holds, a step that waits, and a block
      that throws.
- [ ] **`spec-0201-loops`** — the hand-rolled deadline loops in surefold's suites that wait for a condition
      become `eventually`. The Kafka loops that gather records as they poll stay as they are.
      Done when: each remaining `nanoTime()` deadline in a suite is a gathering loop, named in the PR.

## Acceptance

```bash
./gradlew :surefold-test:check :surefold-junit:check
./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26: build it).

1. ~~Should a try that throws a non-assertion exception also count as not yet?~~ Yes, as today. A service still
   starting often throws, such as a refused connection.
2. ~~Should there be a form that hands back the last value read?~~ Not yet. Nest what needs the value inside the
   block, and spec a value-returning form only if the loops in the second PR show a need.
3. ~~What should the interface be called?~~ `Verdict`, with `held` and `gaveUp(tries, elapsed)`.
