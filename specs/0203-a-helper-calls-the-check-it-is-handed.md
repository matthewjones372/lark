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

- **No change to narrowing blocks.** `withClue { }`, `shouldBeRight { }` and the rest still need their last
  statement to be a check, so a helper called last in one must still return `Expectations`. An example is
  `allOf` in `ShardedJournalSuite`. Spec 0202 says those blocks return `Unit`, and `spec-0202-retire` makes them.
- **No change to the main sources' compilation** outside `surefold-junit`. They never had the flag.
- **No rewrite of existing helpers.** A helper typed `-> Expectations` and one typed `-> Unit` both work.

## Shape

```kotlin
private fun onEveryBackend(check: (StreamBackend) -> Unit) {
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

- **The fix is `spec-0202-retire`.** Once checks return `Unit`, a block of checks is `() -> Unit`, and calling one
  as a statement drops nothing. That entry also retypes the helpers whose blocks return `Expectations`. It adds a
  `surefold-junit` suite with a helper that calls the block it is handed as a statement, and with a test body
  that ends in such a call. The engine test checks that both checks were recorded.
- **The flag stays.** `-Xreturn-value-checker=check` is also the only way to turn on `val _ = …`, which 74 places
  use. Kotlin 2.4's other switch for that feature, `-XXLanguage:+UnnamedLocalVariables`, prints a warning, and
  `allWarningsAsErrors` fails the build on it. Both build files' comments say why the flag stays.
- **The docs stop asking for it.** `docs/testing.md` and the README no longer tell users to add the flag. The
  setup is the power-assert plugin with `expect` among its functions. The story section says a step on a line of
  its own counts, as spec 0202 made it.

## Why this shape

Dropping the flag would fix the helper now, but it would also break `val _ =` in 74 places, many of them in the
unjoin PRs still open. `spec-0202-retire` fixes the helper anyway, and keeps `val _`. Until then the gap only
affects a new helper: the existing ones already compile, because the unjoin PRs typed their blocks `-> Unit`.

**Alternative:** drop the flag now and rewrite each `val _ = x` as a plain `x`. That loses the idiom and the
standard library's dropped-value warnings in tests. Recommended against (maintainer, 2026-10-26).

## Stack

- [x] **`spec-0203-docs`** — `docs/testing.md` and the README stop asking for the flag, and the build comments
      say it stays for `val _`.
      Done when: `grep -n 'return-value-checker' README.md docs/*.md` finds nothing, and `./gradlew build` passes.
- [x] **`spec-0202-retire`** — carries the helper fix and its test; see spec 0202.

## Acceptance

```bash
./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26).

1. ~~Drop the flag now, or fold the fix into `spec-0202-retire`?~~ Fold it into retire. Dropping the flag breaks
   `val _ =`.
2. ~~Should the 17 helpers typed `-> Expectations` move to `-> Unit`?~~ Yes, in `spec-0202-retire`, when
   `Expectations` goes internal.
