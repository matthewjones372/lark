# 0169 — Assertions of our own

## Problem

Every module's tests depend on `io.kotest:kotest-assertions-core`, set once in
the root build. 303 test files import from it, mostly `shouldBe`, `withClue`,
`shouldThrow`, `shouldNotBeNull` and the collection and string matchers.
That is a sizeable library for about forty one-line checks, and its
failure messages and version bumps are not Surefold's to decide. Spec 0168
already moves Surefold off Arrow. The maintainer's direction (chat, 2026-10-22):
Surefold has its own assertions, and the build drops Kotest.

## Not doing

- **No test framework.** Tests stay on JUnit 5. Only the matchers change.
- **No Kotest code, copied or ported.** The assertions are written from
  scratch, in the same spirit as 0168.
- **No property testing, no soft assertions, no `eventually`.** Nothing in
  the tree uses Kotest's versions of these.
- **No change to what any test asserts.** A migration PR changes imports and
  call shapes, never expected values.

## Shape

A new module, `core/surefold-assert`, depends on `surefold`, opentest4j and the Kotlin
stdlib and nothing else. It needs `surefold` for `Either`, and opentest4j so that
IDEs can compare expected and actual (maintainer, 2026-10-23).

A failure names what was expected, what was found, the test's file and line,
and that line's source. Its stack starts at the test's own frame, so the line
that a runner or an IDE names first is the check that failed:

```
expected: Right(value=42)
but was:  Left(value=Bad(why=broken))
at FlockTest.kt:322
    bound shouldBeRight 42
``` It provides infix checks that fail with an `AssertionError` whose
message gives the expected value, the actual value and the clue:

```kotlin
import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.assert.withClue

attempt<NotFound, String> { find(2) } shouldBe NotFound(2).left()
withClue("the loser is interrupted") { loser.wasInterrupted() shouldBe true }
shouldThrow<IllegalStateException> { leaked.fail("late") }.message shouldContain "returned"
```

Surefold's own `Either` gets matchers that say which side was expected and show
the whole actual value, in place of `getOrNull() shouldBe`:

```kotlin
flock<Bad, Int> { 42 } shouldBeRight 42           // expected Right(42) but was Left(Bad(why=broken))
attempt<Bad, Int> { fail(Bad("x")) } shouldBeLeft Bad("x")
val value = attempt<Bad, Int> { 42 }.shouldBeRight()   // the side is asserted, the value returned
```

The checks cover only the matchers the tree uses today, about 40 of them.
The main ones are `shouldBe`, `shouldNotBe`, the null checks, the type and
identity checks, `shouldThrow`, `withClue`, and the collection, map, string
and comparison matchers.

## Why this shape

Every test only needs "compare, and say what differed clearly". Owning that
is a few hundred lines, and it frees the build of a dependency.

- **Names: Kotest's or new ones?** Kotest-shaped names (`shouldBe`, `withClue`)
  make the migration an import rewrite. They're also the general
  vocabulary of Kotlin test DSLs, not one library's design. This is
  recommended. New names would make the migration hand-written across 303
  files.
- **Module: new or inside `surefold-test`?** `surefold-test` promises users it
  doesn't choose an assertion library for them. A separate `surefold-assert`
  keeps that promise, and users can opt in.

## Stack

- [x] **`spec-0169-either-matchers`** ([#488](https://github.com/matthewjones372/lark/pull/488)) — the new module, with
      `shouldBeRight` and `shouldBeLeft` for Surefold's `Either`, plus
      `shouldBe` and `shouldThrow` so that its own tests use nothing else,
      and its `NoOtherDependenciesTest`. It lands first because spec 0168's
      `flock-on-fail` needs it.
      Done when: each matcher has a passing and a failing test, and the
      failing test asserts the message.
- [x] **`spec-0169-surefold-assert-values`** ([#530](https://github.com/matthewjones372/lark/pull/530)) — `shouldNotBe`, the null, type and
      identity checks, `withClue`, the comparisons and the string matchers.
- [x] **`spec-0169-surefold-assert-collections`** ([#531](https://github.com/matthewjones372/lark/pull/531)) — the collection and map
      matchers. Split from one `spec-0169-surefold-assert` entry (maintainer,
      2026-10-24), as the two run past the cap together.
      Done when, for both: every check has a passing and a failing test, and
      the failing test asserts the message.
- [x] **`spec-0169-core-tests`** ([#544](https://github.com/matthewjones372/lark/pull/544)) — `core/` tests move to `surefold-assert`.
- [x] **`spec-0169-stream-actor-tests`** ([#546](https://github.com/matthewjones372/lark/pull/546)) — `stream/` and `actor/` tests
      move.
- [x] **`spec-0169-app-kafka-tests`** ([#549](https://github.com/matthewjones372/lark/pull/549)) — the remaining modules' tests move,
      and Kotest leaves the root build.
      `surefold-app-gradle` and `surefold-app-compiler` stay at 21 for their main code,
      but their tests compile and run on 25 to use `surefold-assert` (maintainer,
      2026-10-24).
      Done when: `grep -r io.kotest` finds nothing outside the docs.
- [x] **`spec-0169-docs`** ([#573](https://github.com/matthewjones372/lark/pull/573)) — `docs/testing.md`, `docs/actors.md`, AGENTS.md
      and the frame filter in `Story.kt` move.

The migration entries are test sources, so they don't count toward the cap.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -rl 'io.kotest' --include=*.kt --include=*.kts .   # empty
```

## Open questions

Answered (maintainer, 2026-10-23: the recommendations stand).

1. ~~Should failures throw opentest4j's `AssertionFailedError`?~~ Revised
   (2026-10-23): yes. Failures carry expected and actual for IDEs to compare,
   plus the test's location and source line.
2. ~~Is the order 0168 then 0169, or interleaved?~~ The `surefold-assert`
   module can land at any time. The test migrations wait until each
   module's 0168 entry has landed, so each test file is rewritten once.
3. ~~Should `surefold-assert` be published?~~ Yes.
4. ~~Should `shouldBe` show a field-by-field diff for data classes?~~ Not in
   the first entry. `toString()` on both sides for now.
