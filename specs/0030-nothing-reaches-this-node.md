# 0030 — Nothing reaches this node

## Problem

`larkWiring` has reported unreachable nodes since it could walk a graph:
`Findings.kt` computes `forgotten(root)` and `Diagnostics.kt` prints
`nothing reaches X, and it is built on every start` as a `WARN`. The compiler
plugin from [0027](0027-a-fault-while-you-type.md) reports only
`LARK_APP_MISSING`. Two checks meant to say the same thing do not, and the one
that answers while you type is the one saying less.

0027's open question 3 left it there, because reachability needs the root and
"the root is the part FIR can least confirm". That is no longer true.
[#43](https://github.com/matthewjones372/lark/pull/43) moved the root into the
declaration — `object Petshop : LarkApp<PelicanServer>()` — and a supertype's
type argument is a `ConeKotlinType` that `keyOf` already renders. The edges are
there too: `Need.by` is the key of the recipe that asked. What is missing is
where a node was written, because `Graph.provides` is a `Set<String>` and has
no source element to underline.

## Not doing

- **No cycle or duplicate diagnostic.** Those stay the task's, as 0027 left them.
- **No change to `Findings.kt`.** The runtime check is the definition here.
- **No suppression mechanism**, and no reachability across Gradle modules.

## Shape

```kotlin
object Petshop : LarkApp<PelicanServer>() {
    override val module: Module =
        singleOf(::ActorPetShop).boundTo<PetShop>() + single { Audit() } + web
    //                                                ~~~~~~~~~~~~~~~~~~
    //  lark-app: nothing reaches Audit, and it is built on every start
}
```

- A warning, because `Severity.WARN` is what `rootFindings` gives it.
  `larkWiring` stays what decides whether a warning fails a build.
- On the call that built the node, which is the line to delete — the anchoring
  and `DEFAULT` strategy `LARK_APP_MISSING` already uses.
- Only the top of each unreached subtree, as `forgotten` does: one forgotten
  `+ web` is one warning, not nine.
- The root key must be one the graph provides, spelled identically. Where it is
  not, the application is given up on and nothing is reported.

## Why this shape

The give-up rule from 0027 is what makes this unlike the missing-key check
rather than more of it. A wrong missing-key is a red line under working code; a
wrong unreachable is a red line under code that is working *and* correct, and
the failure mode is not one warning but every node at once — a root read as
`PelicanServer!` against a declared `PelicanServer` unreaches the whole graph.
Hence the exact match and the silence.

The known divergence is shadowing. `Module.plus` drops a shadowed node and
`Graph.plus` unions keys, so a node reachable only through a shadowed recipe is
unreachable at runtime and reached here. Recommend accepting it: a false
negative costs a warning `larkWiring` prints thirty seconds later, where the
alternative is `Module.shadows` rebuilt in FIR.

## Stack

- [ ] **`spec-0030-the-root-and-what-it-reaches`**
      ([#57](https://github.com/matthewjones372/lark/pull/57)) — `Graph.provides`
      becomes `Map<String, KtSourceElement?>`; the root read from `LarkApp<A>`,
      the walk over `needs`, `forgotten`'s subtree-top rule, and the give-up
      where the root is not a provided key. `verbose` names what is unreached.
      Done when: a forgotten node is named under `LARK_APP_FOUND`, at the top of
      what it took with it, and a graph building no root says nothing at all.
- [ ] **`spec-0030-the-unreachable-warning`**
      ([#58](https://github.com/matthewjones372/lark/pull/58)) —
      `LARK_APP_UNREACHABLE`, a `WARNING` on the providing call, wording shared
      with `Diagnostics.kt`.
      Done when: an unreferenced `single { Audit() }` in the TestKit fixture
      warns on that line without failing `compileKotlin`, and a given-up graph
      stays silent.

The site and the walk were drafted as two entries. They are one: a site nothing
reads is not reviewable on its own, and the two together are under 200 lines.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Exact root match, or erased like the runtime?** `rootOf` matches the erased
   class and errors when two keys share it. Recommend exact, and silence
   otherwise: guessing here is the flood above.
2. **Is a third diagnostic worth a third factory?** Recommend yes, so
   `@Suppress` can name this one alone — but it is the entry to drop if the
   stack has to shrink.
3. **Does `RUNTIME_PROVIDED` reach anything?** `HealthRegistry` is handed in by
   the started graph, so nothing is reachable through it. Recommend a leaf.
4. **What about a node built for its effect?** An application that starts
   `migrations(...)` and never takes a `Migrated` gets a warning that is true
   and unwanted. Recommend leaving it; see **Not doing**.
