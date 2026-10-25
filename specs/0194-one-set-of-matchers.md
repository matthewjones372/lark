# 0194 — One set of matchers

## Problem

Lark has two copies of its matchers. `lark-assert` throws on a failure (spec 0169), and
`lark-junit`'s `Matchers.kt` returns `Expectations` instead (specs 0190 and 0192). Each of the 26
matchers in `lark-junit` repeats its `lark-assert` namesake's test and wording by hand: what counts as
holding, what "expected" says, and how the actual value prints. Only the failure seam (`mismatchAt`,
`failureAt`) is shared.

The copies have already drifted. A failed `shouldNotBe` says `expected: not 1` in a suite and
`expected: anything but 1` everywhere else. Every matcher a module asks for (0192 added nine) is
written twice, and only `lark-assert`'s copy has to match the 0169 tests. A suite author who moves
a test across sees the same check reported two ways.

## Not doing

- **No new matchers.** Parity stays on demand, as 0192 decided. This spec only makes each new
  `Expectations` matcher a one-liner.
- **No change to what passes or fails.** The checks keep the meaning they have now.
- **No merging of the modules.** `lark-assert` stays usable without `lark-junit`, and `Expectations`
  stays in `lark-junit`.
- **No change to `expect`**, `withClue`, `and`, `or` or `!`.

## Shape

Each check is defined once in `lark-assert`, as a function that returns the failure it would report,
or `null` when the check holds. Both families of matchers are built on it:

```kotlin
// lark-assert: the one definition, behind the existing seam opt-in.
@FailureSeam
fun <T> Iterable<T>.missingInAnyOrder(elements: Iterable<T>): Miss? =
    if (counted(this) == counted(elements)) null
    else Miss(actual = toList(), expectedText = "${elements.toList()} in any order")

// lark-assert's throwing matcher
infix fun <T, C : Iterable<T>> C.shouldContainExactlyInAnyOrder(elements: Iterable<T>): C =
    also { missingInAnyOrder(elements)?.let(::thrown) }

// lark-junit's matcher
infix fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(elements: Iterable<T>): Expectations =
    verdict(missingInAnyOrder(elements))
```

- **`Miss`** is a data class with fields `expected`, `actual`, `expectedText` and `cause`: the
  arguments `mismatch` takes today. `thrown` in `lark-assert` and `verdict` in `lark-junit` each turn
  a `Miss` into their own failure at the test's line, through the existing `mismatchAt`.
- **One wording.** Where the copies differ today, `lark-assert`'s wins: it is the one the 0169 tests
  pin. The only known difference is `shouldNotBe`.
- **One test.** `lark-assert`'s tests keep pinning every message. `lark-junit`'s `MatchersTest` keeps
  one test per matcher that checks the pass, the fail and the line. It no longer re-asserts the
  wording.

## Why this shape

A shared check that returns a `Miss` keeps one definition per check. It also keeps `lark-assert`
free of `Expectations`, and keeps the throw out of `lark-junit`.

**Alternative:** `lark-junit` could call the throwing matcher and catch what it throws. That needs
no new type, but the failure would name `Matchers.kt`'s line, not the test's, unless the frame
filter learned about a second module through a thread-local. It would also build a stack trace for
every check that fails on purpose (`!`, `or`).

Recommended: the `Miss` seam.

## Stack

- [x] **`spec-0193-miss`** ([#588](https://github.com/matthewjones372/lark/pull/588)) — `Miss`, `thrown`, and the checks behind `lark-assert`'s values,
      `Either` and comparison matchers, which use them.
      Done when: every 0169 message test passes unchanged.
- [x] **`spec-0193-miss-collections`** ([#597](https://github.com/matthewjones372/lark/pull/597)) — the same for strings, collections and maps.
      Done when: as above.
- [x] **`spec-0193-junit-on-miss`** ([#602](https://github.com/matthewjones372/lark/pull/602)) — `lark-junit`'s `Matchers.kt` is rebuilt on the checks with
      `verdict`, `shouldNotBe` takes `lark-assert`'s wording, and `MatchersTest` drops its copied
      wording assertions.
      Done when: `:lark-junit:test`, `:lark-pekko:test` and `:lark-stream-pekko:test` pass, and no
      expected-text string literal appears in `lark-junit/src/main`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -n '"a string containing\|"no elements\|"at least' core/lark-junit/src/main -r   # empty
```

## Open questions

Answered (maintainer, 2026-10-25: the recommendations stand).

1. ~~Should `Miss` and the checks be public behind `@FailureSeam`, or published as a stable API?~~
   Behind the seam, as `mismatchAt` is today, until a third user appears.
2. ~~What should the checks be named?~~ The short names (`missingInAnyOrder`, `notEqual`,
   `notLessThan`, …), in one `Checks.kt` per family.
3. ~~Should `lark-junit` gain the rest of `lark-assert`'s matchers now?~~ No: they follow when a
   module needs them, as 0192 said.
