# 0243 — Errors collected without forks

## Problem

The only way to keep more than the first error is `parZipOrAccumulate` and `parMapOrAccumulate`, which run each branch
on a fork. Validation is almost always pure: checking a form's fields forks a virtual thread per field, runs them
concurrently for no gain, and hands each branch a scope bound to a thread it did not open, so a branch that touches a
`ThreadLocal`, a transaction or a non-thread-safe builder breaks. Spec 0168 left out "error accumulation beyond what
Surefold already has"; this is the part users miss. Today they either take the forks, or collect by hand into a
`MutableList` and finish with `toNonEmptyListOrNull()?.left()` — as `Module.validate()` (`Plan.kt`) and
`Config.reading` (`Sections.kt`) both do.

## Not doing

- No `Validated`, `Ior` or `Semigroup` type. Errors collect into `NonEmptyList<E>` or fold with `combine: (E, E) -> E`,
  as the `par` forms already do.
- No change to `parZipOrAccumulate` or `parMapOrAccumulate`.
- No accumulation of thrown exceptions: a throw ends the block and goes up unchanged, as in the `par` forms.
- No rewrite of `Plan.kt` or `Sections.kt` here; they can move once this ships.

## Shape

Sequential twins of the `par` forms, same names without `par`, same arities (2 to 9 branches), each with a `combine`
form. Branches run in order on the calling thread; every branch runs; errors come back in branch order.

```kotlin
fun validate(form: Form): Either<NonEmptyList<Invalid>, Account> = attempt {
    zipOrAccumulate(
        { form.name.also { ensure(it.isNotBlank()) { Invalid("name") } } },
        { form.email.also { ensure('@' in it) { Invalid("email") } } },
    ) { name, email -> Account(name, email) }
}

fun validateAll(forms: List<Form>): Either<NonEmptyList<Invalid>, List<Account>> = attempt {
    mapOrAccumulate(forms) { form -> validate(form).bindAll() }   // bindAll: a NonEmptyList's errors, all kept
}

// For a check whose number of rules is not known up front:
fun check(order: Order): Either<NonEmptyList<Invalid>, Order> = attempt {
    accumulate {
        ensureOrAccumulate(order.lines.isNotEmpty()) { Invalid("lines") }
        order.lines.forEach { line -> ensureOrAccumulate(line.qty > 0) { Invalid("qty:${line.sku}") } }
        val total = order.total.bindOrAccumulate()           // answers a placeholder if it failed; see question 3
    }
    order
}
```

- `zipOrAccumulate` / `mapOrAccumulate` live in `ZipOrAccumulate.kt` beside `Accumulate.kt` (which is at detekt's
  40-function cap), receivers
  `Fail<NonEmptyList<E>>` and `Fail<E>` plus `combine`. Each branch runs in its own `attempt`; no `Flight`, no executor.
- `bindAll()` on `Either<NonEmptyList<E>, A>` inside a `Fail<NonEmptyList<E>>`-shaped branch keeps every error rather
  than `mapLeft { it.first() }` as the guide's example does today.
- `accumulate { }` opens an `Accumulating<E>` scope in a `Fail<NonEmptyList<E>>`; `ensureOrAccumulate` and
  `bindOrAccumulate` record and carry on; plain `fail` inside it records and ends the block. At the end, any recorded
  errors fail the outer scope together.
- `docs/core.md`'s "Collecting every error" section leads with the sequential forms and says when `par` is worth it.

## Why this shape

Same names minus `par` means a user who knows one knows both, and switching between them is a rename. **Alternative:**
a single `accumulate { }` DSL and no `zip`/`map` forms — smaller, but it needs placeholders for failed values, which
`zipOrAccumulate` avoids by only calling `transform` when every branch succeeded. Recommended: both, `zip`/`map` first.
The arity overloads are mechanical; they cost lines, not design.

## Stack

- [x] **`spec-0243-zip`** ([#853](https://github.com/matthewjones372/surefold/pull/853)) — `zipOrAccumulate` at arities 2–5, both receiver forms. Split from 6–9 (2026-10-27): the
      sixteen overloads run past the cap together.
      Done when: two failing branches answer with both errors in branch order; every branch ran on the calling thread
      (`Thread.currentThread()` read inside each); a throw in branch 1 stops branch 2 and is rethrown as the same instance.
- [x] **`spec-0243-zip-wide`** ([#854](https://github.com/matthewjones372/surefold/pull/854)) — `zipOrAccumulate` at arities 6–9, both receiver forms.
      Done when: each arity answers its values in order, and its failures in branch order.
- [ ] **`spec-0243-map`** — `mapOrAccumulate` both forms, and `bindAll`.
      Done when: a list with failures at 1 and 3 answers with both, in input order; an empty list answers `emptyList()`.
- [ ] **`spec-0243-accumulate`** — `accumulate { }`, `ensureOrAccumulate`, `bindOrAccumulate`.
      Done when: three failed `ensureOrAccumulate` answer all three; a leaked `Accumulating` is refused like a leaked `Fail`.
- [ ] **`spec-0243-docs`** — `docs/core.md`, `docs/arrow.md` table, `llms.txt`; compiled fence `<!-- core-accumulate -->`.
      Done when: `CoreGuideTest` passes with the sequential example.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :surefold:build :surefold-app:test
```

## Open questions

1. ~~Names: `zipOrAccumulate` / `mapOrAccumulate`, or Surefold's own?~~ Answered (2026-10-27): these. They pair with the `par`
   names already shipped, and 0168 kept `par*OrAccumulate` as they were.
2. ~~Ship `accumulate { }` at all?~~ Answered (2026-10-27): yes, as the last entry, so it can be cut here without touching the
   rest. `Config.reading` is the in-tree case for it.
3. ~~What does `bindOrAccumulate` answer when it failed?~~ Answered (2026-10-27): `A?`. Options were: return `A?` (honest, but every caller writes `!!`
   or `?:`), or Arrow's lazy value that throws if read before the block ends. `A?` has no hidden throw.
4. ~~Arity 9, as `par`, or stop at 5?~~ Answered (2026-10-27): 9, matching `par`, so no one hits a ceiling in one and not the other.
