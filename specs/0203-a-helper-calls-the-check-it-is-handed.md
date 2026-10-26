# 0203 — A helper calls the check it is handed

## Problem

A suite helper that takes a check as a block can't call it as a statement:

```kotlin
private fun onEveryBackend(check: (StreamBackend) -> Expectations) {
    backends.forEach { check(it) }   // e: Unused return value of 'invoke'.
}
```

A body whose last line calls such a block fails the same way ("coerced to 'Unit' due to a functional parameter
type"). A plain helper call, `helper(1)` on a line of its own, compiles.

The cause is `-Xreturn-value-checker=check` on every suite's test compilation and on `surefold-junit`. It was
added so the compiler would reject a dropped `Expectations`. Spec 0202 took `@MustUseReturnValues` off
`Expectations`' files, because the engine now records every check, so the flag catches no dropped check any
more. What it still reports is the standard library's must-use calls, and `Function.invoke` is one of them. So
it rejects a test that is fine.

Spec 0202's stream batch hit this in `HardenedSuite` and changed its helpers' blocks to return `Unit`. Any
helper that takes `-> Expectations` blocks will hit it too: 17 test files have one.

`docs/testing.md` still tells users to add the flag, and still says the checker guards a story step.

## Not doing

- **No change to what a check returns.** `spec-0202-retire` makes checks return `Unit`; this spec doesn't wait for it.
- **No change to narrowing blocks.** `withClue { }`, `shouldBeRight { }` and the rest still need their last
  statement to be a check, so a helper called last in one must still return `Expectations`. An example is
  `allOf` in `ShardedJournalSuite`. Spec 0202 says those blocks return `Unit`, and `spec-0202-retire` makes them.
- **No change to the main sources' compilation** outside `surefold-junit`. They never had the flag.
- **No rewrite of existing helpers.** A helper typed `-> Expectations` and one typed `-> Unit` both work.

## Shape

```kotlin
private fun onEveryBackend(check: (StreamBackend) -> Expectations) {
    backends.forEach { check(it) }
}

object HubSuite : Suite() {
    init {
        test("every backend hands each subscriber every element") {
            onEveryBackend { backend -> backend.hub().drain() shouldBe listOf(1, 2, 3) }
        }
    }
}
```

- **The flag goes** from `build.gradle.kts`'s suite wiring and from `surefold-junit`'s `build.gradle.kts`, with
  the comments that explain it.
- **`docs/testing.md`** no longer asks for the flag. The setup is the power-assert plugin with `expect` among
  its functions. Its story section says a step on a line of its own counts, as spec 0202 made it.
- **A test proves it.** `surefold-junit` gets a suite whose helper calls the block it is handed as a statement,
  and whose test ends in such a call. Its engine test checks that both checks were recorded.

## Why this shape

The flag was there to guard `Expectations`, and since 0202 the record does that. Removing it costs only the
standard library's dropped-value warnings in test code, such as a dropped `map`. Test code didn't have those
before the flag came in for `Expectations`.

**Alternative:** keep the flag and pull `spec-0202-retire` forward, so checks and helpers' blocks return `Unit`.
That is the bigger change: every narrowing check's block type changes, and it waits for the unjoin PRs.
Recommended: drop the flag now. Retire stays as planned.

## Stack

- [ ] **`spec-0203-flag`** — remove the flag in both build files, add the helper suite and its engine test, and
      update `docs/testing.md`.
      Done when: `grep -rn 'return-value-checker' --include=*.kts --include=*.md .` finds nothing, and
      `./gradlew build` passes.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. Should `surefold-junit`'s own main sources keep the flag, to catch a dropped standard-library value in the
   engine? Recommended: no. Nothing in them is marked for it now, and the other modules' main sources never
   had it.
2. Should the 17 helpers typed `-> Expectations` move to `-> Unit` in this PR? Recommended: no. They work once
   the flag is gone, and `spec-0202-retire` retypes them when `Expectations` goes internal.
