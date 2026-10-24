# 0192 — The gaps the pilots found

## Problem

Specs 0189 and 0191 moved 177 tests to `lark-junit`. Both recommend closing the gaps they hit before
the rest of the repo moves:

- **Missing matchers.** Each gap was written another way, with a comment naming the missing matcher.
  The 10 workarounds in `lark-stream-pekko` use `expect(a < b)`, `.sorted() shouldBe …` or
  `shouldNotBe null`. These show no expected and actual for the IDE, and they don't read as what they
  check.
  - `shouldContainExactlyInAnyOrder` (4);
  - `shouldNotBeNull` (2);
  - `shouldBeEmpty` (1);
  - the comparisons `shouldBeLessThan`, `shouldBeGreaterThan` and `shouldBeGreaterThanOrEqual` (3).
- **`@TempDir` has no equivalent.** `ReadmeExampleSuite` makes and deletes its own directories by hand.
- **The guide doesn't warn about suite state.** Jupiter makes a fresh class instance for each test, but
  a suite is one `object`. `DefectTest` kept an exception in a field, so after the move every test
  attached its note to the same exception, and three tests failed. Nothing in `docs/testing.md` warns
  about this.

## Not doing

- **No other lark-assert matchers.** `shouldStartWith`, `shouldHaveSize`, the map matchers and the
  rest follow when a module needs them.
- **No moving more modules.** That is its own spec.
- **No change to how Pekko's testkit checks report.** Like any other throwing check, they stay errors.

## Shape

```kotlin
test("both feeds arrive") { pekko -> merged.collected(pekko) shouldContainExactlyInAnyOrder listOf(1, 2, 3, 4) }
test("the root is found") { _ -> findRoot().shouldNotBeNull { root -> root.name shouldBe "lark" } }
test("nothing is declined") { _ -> declines.shouldBeEmpty() }
test("the first element waits") { _ -> elapsed shouldBeGreaterThanOrEqual after }
test("it compiles") { _ -> withTempDirectory { dir -> compiles(source, into = dir) } }
```

- **The matchers** sit in a new file, `lark-junit`'s `Matchers.kt`, and return `Expectations`. Each
  one fails with the same wording as lark-assert's version, names the test's line, and carries the
  actual value.
  - `shouldContainExactlyInAnyOrder` takes an iterable, or `vararg` elements, and compares counts, as
    lark-assert's does.
  - `shouldNotBeNull { a -> … }` narrows, as `shouldBeInstanceOf` does. Its lambda runs only on a
    value. A form with no lambda is included too.
  - `shouldBeEmpty` and `shouldNotBeEmpty` work on an `Iterable` and on a `Map`.
  - The comparisons are `shouldBeLessThan`, `shouldBeLessThanOrEqual`, `shouldBeGreaterThan` and
    `shouldBeGreaterThanOrEqual`, for any `Comparable`.
- **`withTempDirectory { dir -> … }`** makes a directory, passes it to the block, deletes it
  recursively afterwards, and returns the block's result. A test that needs a directory calls it;
  nothing is shared between tests.
- **The guide** gets a short "State in a suite" section in `docs/testing.md`'s Suites part. It says a
  suite's properties are shared by every test in it, and that per-test state belongs in the test or in a
  function the test calls.

## Why this shape

A matcher per gap keeps each check carrying its actual value, which `expect` can't do. A separate
`Matchers.kt` keeps `Expectations.kt` to the type and its combinators, now that it holds a dozen
matchers.

For the directory, a function beats a `Resource`. A `Resource` would be shared across the suite, but
`@TempDir` gives each test its own directory, and that's what `ReadmeExampleSuite` needs.

## Stack

- [ ] **`spec-0192-matchers`** — `Matchers.kt` with the matchers above, moved and new.
      Done when: each matcher's pass and failure is tested, and a failure names the test's line and
      carries the actual value.
- [ ] **`spec-0192-tempdir-and-docs`** — `withTempDirectory`, the "State in a suite" section, and the
      new matchers in `docs/testing.md`'s matcher list.
      Done when: a test shows the directory deleted after both a pass and a throw, and the guide test
      compiles the docs.
- [ ] **`spec-0192-use`** — `lark-stream-pekko`'s 10 workarounds, and `ReadmeExampleSuite`'s
      hand-made directories, replaced with the new matchers and `withTempDirectory`.
      `StreamSuite`'s comment on the probe says that Pekko's check throws, not that a matcher is missing.
      Done when: `:lark-stream-pekko:test` reports the same 192 tests, all passing, and no comment
      says a matcher is missing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -rn "lark-junit has no" stream lark-pekko   # nothing
```

## Open questions

Taken as recommended (maintainer, 2026-10-24: build it straight away).

1. ~~Move the existing matchers into `Matchers.kt` too, or only the new ones?~~ Move them all. That
   leaves `Expectations.kt` with the type, `expect` and the combinators.
2. ~~Should `withTempDirectory` be a `Resource` as well?~~ No. A suite that wants one shared directory
   can install one with `install`.
3. ~~`shouldBeEmpty` on `String` too?~~ No. Neither pilot needed it, and `shouldBe ""` says the same.
