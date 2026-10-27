# 0200 — A check that throws

## Problem

Some checks report by throwing, and return nothing:
- Playwright's `assertThat(locator).hasText(…)`;
- pelican's `Golden` and `StubFile.writeTo`;
- WireMock's `verify`.

A `surefold-junit` test must return its `Expectations`, so a test built on these has nothing to return. Today it
returns a check that checks nothing:

```kotlin
assertThat(page.locator("#load")).hasText("Start load")
// Playwright's assertions wait for the page, and throw if it never gets there: reaching here, each held.
expect(true)
```

`surefold-bank`'s two page suites do this, and so do petshop's `GoldenSuite` and `DemoStubsSuite`. `expect(true)`
gets past the return-value checker without checking anything, which is the hole the checker exists to close
(spec 0175). It also loses failures: the first throw ends the test, so a second check in it never runs.

## Not doing

- **No catching of other errors.** Only an `AssertionError` is a failed check. Anything else still errors the
  test, as it does today.
- **No value handed on.** The block returns `Unit`. A check that returns what it found, such as pelican's
  `shouldBeOk`, is a later spec if one is wanted.
- **No change to `shouldThrow`**, which checks that something does throw.

## Shape

```kotlin
test("the load raises the transfers-a-second tile") {
    page.click("#load")
    holds { assertThat(page.locator("#load")).hasText("Start load") } and
        holds { assertThat(page.locator("#tps")).not().hasText("0") }
}

test("a pet, and a refusal, look on the wire the way they did") {
    holds { golden.exchange("get-pet", client, getPet, 1L) } and
        holds { golden.exchange("adopt-missing-pet", client, adoptPet, 404L) }
}
```

- `holds(block: () -> Unit): Expectations` runs `block`. It passes as one check if `block` returns, and fails
  if `block` throws an `AssertionError`.
- **A failure:** keeps the thrown message and, for an opentest4j `AssertionFailedError`, its expected and
  actual. It names the test's line that called `holds`, as the other matchers do, and keeps the throw as its
  cause.
- `holds { }` blocks join with `and` like any check, so each one's failure is reported.

## Why this shape

A block that throws is how these libraries check. `holds` turns one such block into one check, without
pretending it passed.

**Alternative:** an overload `expect { … }`. It reads well, but `expect(condition)` and `expect { block }`
would mean different things under one name, and a block returning `Boolean` would be ambiguous. Recommended:
`holds`.

## Stack

- [x] **`spec-0200-holds`** ([#662](https://github.com/matthewjones372/surefold/pull/662)) — `holds { }` in `surefold-junit`, with tests and a sample: a block that returns, one
      that throws an `AssertionError`, one that throws `AssertionFailedError` with expected and actual, and one
      that throws something else. Also a line in `docs/testing.md`'s matcher list, and `surefold-bank`'s two page
      suites on it, without their `expect(true)`.
      Done when: `:surefold-junit:check` passes, and `grep -rn 'expect(true)' examples/surefold-bank` finds nothing.

## Acceptance

```bash
./gradlew :surefold-junit:check :surefold-bank:check
```

## Open questions

Taken as recommended (maintainer, 2026-10-25).

1. ~~What should it be called?~~ `holds`. `shouldNotThrow` reads as if any throw counts, and `asserting` reads as
   if it asserts something itself.
2. ~~Should an `AssertionError` thrown from another thread inside the block count?~~ No. Only what the block
   itself throws.
