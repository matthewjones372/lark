# 0169 — Assertions of our own

## Problem

Every module's tests depend on `io.kotest:kotest-assertions-core`, set once in
the root build. 303 test files import from it, mostly `shouldBe`, `withClue`,
`shouldThrow`, `shouldNotBeNull` and the collection and string matchers.
That is a sizeable library for about forty one-line checks, and its
failure messages and version bumps are not Lark's to decide. Spec 0168
already moves Lark off Arrow. The maintainer's direction (chat, 2026-10-22):
Lark has its own assertions, and the build drops Kotest.

## Not doing

- **No test framework.** Tests stay on JUnit 5. Only the matchers change.
- **No Kotest code, copied or ported.** The assertions are written from
  scratch, in the same spirit as 0168.
- **No property testing, no soft assertions, no `eventually`.** Nothing in
  the tree uses Kotest's versions of these.
- **No change to what any test asserts.** A migration PR changes imports and
  call shapes, never expected values.

## Shape

A new module, `core/lark-assert`, depends on the Kotlin stdlib and nothing
else. It provides infix checks that fail with an `AssertionError` whose
message gives the expected value, the actual value and the clue:

```kotlin
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldThrow
import io.github.matthewjones372.lark.assert.withClue

attempt<NotFound, String> { find(2) } shouldBe NotFound(2).left()
withClue("the loser is interrupted") { loser.wasInterrupted() shouldBe true }
shouldThrow<IllegalStateException> { leaked.fail("late") }.message shouldContain "returned"
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
- **Module: new or inside `lark-test`?** `lark-test` promises users it
  doesn't choose an assertion library for them. A separate `lark-assert`
  keeps that promise, and users can opt in.

## Stack

- [ ] **`spec-0169-lark-assert`** — the new module with its checks and
      tests, plus its `NoOtherDependenciesTest`.
      Done when: every check has a passing and a failing test, and the
      failing test asserts the message.
- [ ] **`spec-0169-core-tests`** — `core/` tests move to `lark-assert`.
- [ ] **`spec-0169-stream-actor-tests`** — `stream/` and `actor/` tests
      move.
- [ ] **`spec-0169-app-kafka-tests`** — the remaining modules' tests move,
      and Kotest leaves the root build.
      Done when: `grep -r io.kotest` finds nothing outside the docs.
- [ ] **`spec-0169-docs`** — `docs/testing.md`, `docs/actors.md`, AGENTS.md
      and the frame filter in `Story.kt` move.

The migration entries are test sources, so they don't count toward the cap.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -rl 'io.kotest' --include=*.kt --include=*.kts .   # empty
```

## Open questions

1. **Should failures throw opentest4j's `AssertionFailedError`, so IDEs show
   a side-by-side diff?** That puts `opentest4j` on `lark-assert`'s
   classpath. JUnit brings it to every test classpath already.
   Recommended: no for now. Use a plain `AssertionError` with a readable
   message, and revisit if the diffs are missed.
2. **Is the order 0168 then 0169, or interleaved?** Recommended: 0169's
   first entry lands any time, since it touches nothing else. The test
   migrations wait until 0168's `core-off-arrow`, so each test file is only
   rewritten once.
3. **Should `lark-assert` be published for users, or kept internal?**
   Recommended: publish it. It costs nothing extra and gives `lark-test`
   users a matching set.
4. **Should data classes get a field-by-field diff in the `shouldBe`
   message?** Recommended: not in the first entry. Print `toString()` on
   both sides and add a diff later if it's needed.
