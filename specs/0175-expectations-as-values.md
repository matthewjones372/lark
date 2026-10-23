# 0175 — Expectations as values

## Problem

In spec 0174's `lark-junit`, a test fails by throwing. That has two costs. A test stops at its first
failed check, so the failures after it go unreported. A test that checks nothing passes without
complaint. ZIO Test fixes both: a test returns a result value, and the compiler rejects a test that
doesn't return one. The maintainer wants the same here (2026-10-23), with a well-rendered error.

## Not doing

- **No matchers beyond `lark-assert`'s.** `expect(Boolean)` covers every check; `shouldBe` and its kin
  return `Expectations` built from `lark-assert`'s failures. `lark-assert` keeps its throwing checks.
- **No Lark compiler plugin.** Rendering comes from JetBrains' power-assert plugin.
- **No change to `story` or `lark-test`.** That is the follow-up spec named in 0174.

## Shape

`lark-junit` gains a dependency on `lark-assert`. A test body becomes `(R) -> Expectations`.

```kotlin
test("an order is saved once") { db ->
    db.save(order)
    expect(db.count() == 1) and expect(db.find(order.id) == order)
}
test("does not compile") { db -> db.save(order) }                  // Unit is not Expectations
test("does not compile either") { db -> expect(db.count() == 1); expect(true) }   // first one dropped
```

```
an order is saved once: 1 of 2 expectations failed
expect(db.count() == 1)
       |  |       |
       |  2       false
       Database(orders=2)
at OrderSuite.kt:6
```

- **Values:** `and` keeps every failure, `or` passes if either side does, and `!` negates.
- **Must use:** `Expectations` is marked for Kotlin's return-value checker. With the repo's
  `allWarningsAsErrors`, a dropped expectation is a compile error.
- **Rendering:** the power-assert plugin, with `expect` listed in its functions, rewrites each call to
  pass the diagram above as its message. Each failure is a `lark-assert` `AssertionFailedError` with the
  test's file and line. Expected and actual for IntelliJ's diff come from value-returning `shouldBe`-style
  matchers built on `lark-assert`, not from power-assert: nothing joins `lark-junit`'s runtime classpath.
  Several failures are reported as one `MultipleFailuresError`. Without the plugin, `expect` still quotes
  the source line, as `lark-assert` does.
- **Throwing:** a `shouldBe` that throws inside a test is allowed. It stops the test and is reported as
  an error.

## Why this shape

Expectations as values beat throwing checks: every failure is reported, and the compiler rejects a test
that checks nothing. Power-assert is the renderer because it's JetBrains' own plugin, it adds nothing to
the runtime classpath, and one `expect` covers every check. Typed matchers (`expect(x).toEqual(y)`)
would need no plugin, but they can't show subexpression values. A Lark compiler plugin like
`lark-app-compiler` could do what ZIO's macros do, but it's far more code to own.

## Stack

Builds on 0174's `spec-0174-engine`.

- [ ] **`spec-0175-expectations`** — `Expectations`, `expect`, `and`/`or`/`!`, the `(R) -> Expectations`
      body, must-use, and reporting as `MultipleFailuresError`.
      Done when: two failing expectations are both reported, and a compile test shows that a `Unit` body
      and a dropped `expect` are both rejected.
- [ ] **`spec-0175-power-assert`** — the power-assert plugin applied to `expect`, plus value-returning
      `shouldBe`-style matchers that carry expected and actual.
      Done when: a golden test matches the diagram above, and a failed `shouldBe` carries expected and actual.
- [ ] **`spec-0175-docs`** — the `docs/testing.md` section shows the plugin setup and an `expect` example.
      Done when: the guide test compiles the example.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-junit:test --tests '*ExpectationsCompileTest*'
```

## Open questions

Answered (maintainer, 2026-10-23).

1. ~~What renders a failure?~~ power-assert.
2. ~~How is a dropped expectation rejected?~~ Kotlin's return-value checker. If it's still
   experimental on 2.4.10, detekt's `IgnoredReturnValue` rule is the fallback.
3. ~~Can a throwing `shouldBe` appear inside a test?~~ Yes, and it's reported as an error.
4. ~~Can the engine get expected and actual from power-assert for IntelliJ's diff?~~ No structured API:
   it would add `kotlin-power-assert-runtime` to the runtime classpath (maintainer, 2026-10-23: use
   value-returning matchers built on lark-assert instead).

Still open:

5. **What does a user's build need when the return-value checker isn't on?** Recommend that the guide
   gives the compiler flag next to the plugin, and says that without the flag a dropped expectation
   compiles but is lost.
