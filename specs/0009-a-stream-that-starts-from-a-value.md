# 0009 — A stream that starts from a value

## Problem

A pipeline that began as `Source.completionStage(stage)` — the older name is
`fromCompletionStage` — ran to `Done` having processed nothing, because the
stage completed with `null`, Pekko emits nothing for a null value, and an
empty source is a normal outcome. Every builder that reads absence as
emptiness (`from(Optional)`, `fromJavaStream` over `ofNullable`, the stage
ones) has the same shape of failure. Nothing in the types said the start was a lookup that could
miss, and nothing at runtime said that zero elements was the wrong answer.
The maintainer's instruction (chat, 2026-09-04): do as much as the library
can against that.

## Not doing

- **No inspection of a raw `Source`.** One built elsewhere is opaque; lark
  cannot tell a source built from `null` from one with no rows.
- **No emptiness failure by default.** An empty stream is the normal outcome
  of most pipelines; making it loud is a decision the caller states.
- **No `fromNullable`.** The builder that turns absence into emptiness is the
  mistake; the library does not ship it.

## Shape

```kotlin
Stream.single(customer)                     // customer: Customer? → does not compile; the ?: is written at the source
Stream.of(a, b, c)

val one: Stream<Missing, Customer> =
    lookup(id)?.let { Stream.single(it) } ?: Stream.fail(Missing(id))     // absence is a failure with a name

Stream.from(source).orFailIfEmpty(Missing(id))                            // a source lark did not build: zero elements → Failed(Missing)

Stream.fromStage(stage)                                                    // Stream<Nothing, A>: a null completion is Died(NullPointerException), never Done with nothing
Stream.fromStage(stage, ifNull = Missing(id))                              // Stream<Missing, A>: a null completion is Failed(Missing(id))
```

- `Stream.single(element: A): Stream<Nothing, A>` and
  `Stream.of(vararg elements: A): Stream<Nothing, A>`, `A : Any` as every
  element type is — so a nullable does not compile, and the choice between
  a value and `Stream.fail` is made where the value is.
- `Stream<E, A>.orFailIfEmpty(error: E2): Stream<E | E2, A>` (widening as
  `via` does): Pekko's `Source.orElse`, which switches to the alternative only
  when the primary completes without emitting, with the alternative
  `Source.failed(DeclaredFailure(error))`. `run` answers `Failed(error)`.
- `Stream.fromStage(stage: CompletionStage<A>): Stream<Nothing, A>` and
  `Stream.fromStage(stage, ifNull: E): Stream<E, A>`, `A : Any`. The check is
  at runtime, because a stage from Java may complete with `null` whatever its
  type argument says: the first form dies with a `NullPointerException` whose
  message names the builder, the second fails with the error named. A failed
  stage is `Died(cause)`, unwrapped as `await()` unwraps.
- `DoesNotCompileTest` gains two fixtures asserting the compiler's own words:
  `Stream.single(x)` with `x: String?`, and `Stream.from(listOf(x))`.
- Docs: a paragraph under **Building** — *missing is a failure, not an empty
  stream* — with the three lines above; and a detekt snippet a consumer can
  paste into `ForbiddenMethodCall` naming the builders that read absence as
  emptiness (`Source.completionStage`, `Source.fromCompletionStage`,
  `Source.future`, `Source.from(Optional)`, `Source.fromJavaStream` over
  `Stream.ofNullable`, `Source.fromIterator` over an `Optional`), since the
  one thing lark cannot reach is code that never enters it.

## Why this shape

Compile time where lark builds the stream, an opt-in word where it does not,
and a paste-in lint for the code that never arrives: that is the whole of
what a library can do about a value that was `null` before it got there. The
alternative — `orFailIfEmpty` by default with an `allowEmpty()` escape — makes
every legitimately empty pipeline say so, which is most of them.

## Stack

- [x] **`spec-0009-single`** (`main`, 4ce39fb) — `single`, `of`, the two fixtures.
      Done when: both fixtures fail to compile with the asserted wording, and
      `Stream.of()` with no arguments is `Stream.empty()`'s twin.
- [x] **`spec-0009-stage`** (`main`, 3d3c5dd) — `fromStage`, both forms.
      Done when: a stage completing with `null` is `Died` in the first form
      and `Failed(error)` in the second, a value is one element, and a failed
      stage is `Died(cause)` with the same instance.
- [x] **`spec-0009-if-empty`** (`main`, 30336ff) — `orFailIfEmpty`, the docs paragraph, the
      detekt snippet.
      Done when: an empty raw source through `orFailIfEmpty` runs to
      `Failed(error)`, a non-empty one is unchanged, and a failing one is
      still `Failed` with its own error, not the emptiness one.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **`orFailIfEmpty` on `Pipe` too?** A pipe cannot know whether its source
   emitted; Pekko's `orElse` is a source operator. Recommended: `Stream` only,
   with the reason in KDoc.

Decided while building (2026-09-04): `orFailIfEmpty` is `orElse` over a
`lazySource` of the failed source, since `Source.failed` fails at
materialisation and `orElse` would never look at the primary; `fromStage`
guards the null in the stage itself, on `Source.completionStage`; the lint
lands in this repository's detekt config too, with `fromStage`'s one call the
annotated exception; Pekko 1.2.1 has no `Source.from(Optional)`, so the
snippet names `fromJavaStream` and `fromIterator` as the routes an `Optional`
takes.
