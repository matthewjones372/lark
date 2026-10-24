# 0190 — Throws and contains as values

## Problem

Spec 0189's pilot found that `lark-junit` has no value-returning `shouldThrow` or `shouldContain`. Kotest's
versions appear in 82 test files, and lark-assert's `shouldContain` in over 800 calls. In the pilot,
`expect("…" in message)` stood in for `shouldContain`, and for `shouldThrow` it took 5 lines:

```kotlin
val refused = try { system.larkDispatcher("pooled"); null } catch (e: IllegalArgumentException) { e }
expect("pekko.actor.pooled.executor" in refused?.message.orEmpty())
```

That `expect` shows no expected and actual for the IDE's diff, and a missing exception only shows as
an empty message. Spec 0189 asks for both matchers before another module moves.

## Not doing

- **No `withClue`.** `expect(cond) { "clue" }` already covers it.
- **No other lark-assert matchers.** `shouldHaveSize`, `shouldStartWith` and the map matchers follow
  only if the next pilot needs them.
- **No change to lark-assert.** Its throwing matchers stay as they are.
- **No moving the repo's tests.** The `lark-stream-pekko` pilot is its own spec.

## Shape

```kotlin
test("a fork-join-executor dispatcher is refused") { system ->
    shouldThrow<IllegalArgumentException>({ system.larkDispatcher("pooled") }) { refusal ->
        refusal.message shouldContain "pekko.actor.pooled.executor"
    }
}
test("the id defaults to lark") { system -> system.larkDispatcher() shouldBeSameInstanceAs system.larkDispatcher("lark") }
test("an order's events are recorded") { db -> db.events(order.id) shouldContain Saved(order.id) }
```

- **`shouldThrow<T>(block)`** returns `Expectations`. It holds when `block` throws a `T`. If
  `block` throws nothing, the failure reads "expected: a T thrown / but was: nothing thrown". If it
  throws something else, the failure carries that throwable as its cause. Like lark-assert's
  `shouldThrow`, it names the test's line.
- **`shouldThrow<T>(block) { e -> … }`** runs the second lambda only when `block` threw a `T`, and
  returns that lambda's `Expectations`, counted together with the throw check. On a failure, the
  lambda doesn't run.
- **Fatal throwables pass straight through.** An interrupt, a cancellation, or a VM error that is not a
  `T` is rethrown, using lark's `nonFatalOrThrow`, so a `timeout` aspect still stops the test.
- **`shouldContain`** works on a `String?` with a part, and on an `Iterable<T>` with an element. The
  failure reads the way lark-assert's does and carries the actual value. `shouldNotContain` comes with
  it.
- **`shouldBeSameInstanceAs`** and `shouldNotBeSameInstanceAs` compare with `===`.

## Why this shape

The exception has to come back to the test, because most callers then check its message. The options
were:

- **A trailing lambda** that receives the exception. It keeps the result one `Expectations`, and
  never hands the test a `null`. Recommended.
- **A returned pair** of `Expectations` and `T?`. That makes every caller unwrap a nullable, and one
  of the pair is easy to drop.
- **Throwing on a missing exception**, as lark-assert does. Then a missing exception stops the test,
  and the failures after it go unreported.

The matchers use the same names as lark-assert's, so a module moves by changing an import.

## Stack

- [ ] **`spec-0190-throws`** — `shouldThrow`, in both forms, with fatal throwables passed through.
      Done when: tests cover a matching throw, a missing throw, a wrong type (carried as the cause), an
      interrupt that is rethrown, and a second lambda that doesn't run on a failure. Kover stays at 90%.
- [ ] **`spec-0190-contains`** — `shouldContain` and `shouldNotContain` for strings and iterables,
      and `shouldBeSameInstanceAs` and `shouldNotBeSameInstanceAs`.
      Done when: each matcher's failure carries the actual value, and names the test's line.
- [ ] **`spec-0190-pilot-tidy`** — `lark-pekko`'s suites use the new matchers in place of the
      `try`/`catch` and `in` workarounds. `docs/testing.md` lists the matchers.
      Done when: `:lark-pekko:test` still reports the same 6 tests passing, and the guide test compiles
      the example.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-junit:test :lark-pekko:test
```

## Open questions

1. ~~**Should a wrong exception type be a failure or an error?**~~ Answered (maintainer, 2026-10-24): a
   failure, with the actual throwable as its cause. A test that expected a throw and got a different one
   checked something and found it wrong.
2. ~~**Should `shouldBeSameInstanceAs` be in this spec?**~~ Answered (maintainer, 2026-10-24): yes.
3. ~~**A test that imports both lark-assert's and lark-junit's `shouldThrow` gets an ambiguous call. Is
   that acceptable?**~~ Answered (maintainer, 2026-10-24): yes, as for `shouldBe`. The guide says to
   import one package's matchers.
