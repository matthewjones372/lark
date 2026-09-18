# 0031 — Two recipes, and a ring

## Problem

`larkWiring` reports four kinds of fault. After
[0030](0030-nothing-reaches-this-node.md) the compiler plugin reports two, and
0030 says why not the rest: "cycles and duplicates stay the task's". They are
the two the reader can most easily get wrong, not the two that matter least — a
cycle is a `FAIL`, and it is the fault a reader can do least with, since
`Diagnostics.kt` gives it no site at all.

The reader already holds what both need. `Graph.needs` is an edge list keyed by
the recipe that asked, which is what Kahn's algorithm walks; and `Graph.plus`
already sees a key arriving twice, it just unions the map and says nothing.

What it does not hold is which merges were *meant* to collide. `Module.plus`
keeps every collision as a `Shadow` and `overriding` forgives the ones its own
merge introduced; the reader maps `overriding` onto `plus` and cannot tell them
apart.

## Not doing

- **No `overriding` of a key nothing provides.** `Subgraph.kt` throws on that at
  assembly. A second spec, if anyone wants it sooner.
- **No cycle shortest-path.** Report the cycle Kahn's leaves stalled, as the
  runtime does, not the prettiest one in it.
- **No new severity.** Duplicate is a `WARNING`, cycle an `ERROR`, matching
  `Findings.kt`.

## Shape

```kotlin
object Petshop : LarkApp<PelicanServer>() {
    override val module: Module =
        single<Shop> { Shop(orders()) } + single<Shop> { Shop(fake()) }
    //                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //  lark-app: Shop is provided twice; this one wins over Wiring.kt:41
}
```

- On the call that **wins**, which is `Diagnostics.kt`'s own choice of site: it
  is the line a reader edits to stop the other being shadowed.
- `overriding(...)` and `overridingConfig(...)` forgive the collisions their own
  merge introduces, exactly as `Module.shadowing` does. A base module that
  already shadowed a key keeps saying so.
- A cycle is reported on one node in it, which is more than the task manages —
  `Diagnostics.where` has no site for a cycle and prints the sentence bare.

## Why this shape

The trap is the `when` union, and it is the reason this is a separate spec
rather than a paragraph in 0030. The reader merges every branch of a choice into
one graph, because a key is missing only where *every* branch misses it. That
union is right for missing keys and wrong for both faults here:
`if (x) single<A> { … } else single<A> { … }` provides `A` twice in the union and
once in every real assembly, and the same union can close a ring that no branch
contains. The repository's own `COMPOSED` test fixture is that `if`.

So a branch union marks the keys it merged as alternatives, and neither check
counts an alternative as a collision or as an edge into a ring. Where that is
not enough to be sure, give up on the application — the rule 0027 set and 0030
kept: `larkWiring` still fails the build, and a warning under correct code is
the one thing that teaches a reader to ignore the colour.

The alternative is to read a choice as several graphs and check each. It is the
honest model, and it is exponential in the number of `when`s in a module.
Recommend the marking, and give up where a key is provided both inside a choice
and outside one.

## Stack

- [ ] **`spec-0031-two-recipes-one-key`** — `Graph` gains shadows; `plus`
      records a collision, `overriding` and `overridingConfig` forgive their
      own, a branch union marks alternatives. `LARK_APP_DUPLICATE`, a `WARNING`
      on the winning call.
      Done when: two `single<Pump>` in one chain warn on the second; the same
      pair under `overriding` does not; and `COMPOSED`'s `if` stays silent.
- [ ] **`spec-0031-a-cycle-while-you-type`** — Kahn's over `needs`, ignoring
      alternative edges. `LARK_APP_CYCLE`, an `ERROR` on one node of the ring,
      in `Diagnostics.kt`'s wording.
      Done when: two recipes that need each other fail `compileKotlin` naming
      both keys, and a graph the reader gave up on compiles clean.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. ~~**How is the shadowed site written?**~~ Answered by building it:
   `FirFile.sourceFileLinesMapping` turns an offset into a line without naming a
   PSI type, so 0027's relocated-PSI hazard is not reached. The mapping is
   nullable and falls back to `another`, which is the word the runtime report
   already uses for a site it lacks.
2. **Which node of a cycle gets the error?** Recommend the first in the path
   Kahn's leaves stalled, sorted, so the same graph underlines the same line
   twice running.
3. **Is `boundTo` a collision?** `keyedAs` collapses a graph to one key, so two
   `boundTo<B>()` in a chain collide at `B` while their sources differ.
   Recommend yes, a collision — it is one at runtime.
4. **Give up, or stay silent per-key?** Where a key is provided both inside and
   outside a choice, recommend abandoning the whole application rather than
   skipping that key: a half-checked graph is the thing 0027's `LARK_APP_UNREAD`
   exists to never be.
