# 0147: one wiring graph for lark-app and the compiler plugin

## Problem

The rules for checking a wiring graph are written twice, once per key type:

| Rule | lark-app (`KType`) | lark-app-compiler (`String`) |
|---|---|---|
| missing keys | `Plan.kt:44` | `Reader.kt:140` |
| layering and cycle | `Plan.kt:96-121` | `Reader.kt:162-189` |
| unreached keys | `Findings.kt:40` | `Reader.kt:149` |
| runtime-provided keys | `Health.kt:22` | `Reader.kt:373` |

Only comments keep the two in step. One says "Kahn's algorithm, as `Plan.kt`
runs it … the same list in the same order"; another says "`Findings.forgotten`
names the same set". The compiler module has no test of these rules (only
`VersionsTest`). Its agreement with the runtime is checked end to end in
`LarkWiringPluginTest`.

The sentences are written several times too:

- **Missing key:** "missing X / for Y" (`Plan.report`), "error: missing X"
  (`Findings.lines`), and "Y needs X, and nothing builds it" (compiler
  `ApplicationChecker.said`).
- **Cycle arrow:** `→` at runtime, `->` in the compiler (`ringSaid`).

## Not doing

- Changing `WiringError`, `Finding` or `Plan`, which are public and keyed by
  `KType`. The runtime adapter maps the graph's results onto them.
- Changing how the compiler plugin reads FIR into a graph (`read`, `keyedAs`,
  `or`, `overriding`).
- Changing how a choice is checked. At runtime a choice is one node providing
  `P` (spec 0136). The compiler unions its branches, because it cannot know
  which branch runs. Both models stay. The graph takes `alternatives`, and the
  runtime passes none.

## Shape

```kotlin
// new module lark-app-graph: plain Kotlin, depends on nothing but the stdlib
class WiringGraph<K : Comparable<K>, Site>(
    val nodes: Map<K, Needs<K, Site>>,   // what each key needs, and where it was written
    val provided: Set<K> = emptySet(),   // keys the running app hands in itself
    val alternatives: Set<K> = emptySet(),
) {
    fun missing(): List<Missing<K, Site>>
    fun layers(): List<List<K>>?         // null when there is a cycle
    fun cycle(): List<K>?                // none through an alternative
    fun unreached(root: K): List<K>      // tops of unreached subtrees only
}

object Said {                            // the one wording, both sides
    fun missing(key: String, by: String): String
    fun cycle(ring: List<String>): String // `→`
    fun twice(key: String, wins: String, shadowed: String): String
    fun unreached(key: String): String
}

const val HEALTH_REGISTRY = "io.github.matthewjones372.lark.app.HealthRegistry"
```

- `Module.validate` and `findings` build a `WiringGraph<KType, String?>`.
  `Start.build` asks it for `layers()` each time it expands a choice.
- The FIR `Graph` turns into a `WiringGraph<String, KtSourceElement?>` before
  checking.
- Every report (prose, `e: file://`, FIR) formats sentences from `Said`.

## Why this shape

Putting the type parameters on the graph lets it hold both kinds of key without
either side learning the other's types. Today the comments are the only seam
between the two copies, and nothing enforces them. Sharing source files through
a Gradle `srcDir` was weighed in chat and rejected: it hides the dependency, and
the shared code would get no tests of its own.

## Stack

- [ ] **`spec-0147-graph`**: the `lark-app-graph` module with `WiringGraph`
      and direct tests for missing, layers, cycle (including through an
      alternative) and unreached.
      Done when: its tests pass and it depends on nothing but the stdlib.
- [ ] **`spec-0147-runtime`**: `validate`, `findings` and `Start.build` run on
      it. `runtimeProvided` comes from `HEALTH_REGISTRY`.
      Done when: `ValidateTest`, `FindingsTest`, `StartTest` and
      `ChoiceTest` pass, and `NoOtherDependenciesTest` still allows only
      `lark*`.
- [ ] **`spec-0147-compiler`**: the compiler checks through `WiringGraph`.
      `stalled`, `walk` and `reached` are deleted from `Reader.kt`.
      Done when: `LarkWiringPluginTest` passes, and the plugin loads in a
      consumer build (see question 1).
- [ ] **`spec-0147-said`**: `Said` everywhere, with `→` on both sides.
      Done when: no wiring sentence is spelled out outside `Said`, and the
      golden reports in `DiagnosticsTest` and `LarkWiringPluginTest` are
      updated once.

## Acceptance

```bash
./gradlew :lark-app-graph:test :lark-app:test :lark-app-compiler:test :lark-app-gradle:test
```

## Open questions

1. How does `lark-app-graph` reach the compiler plugin's classpath? The plugin
   is resolved by coordinates (`LarkCheckerPlugin.kt:28`), and I have not
   checked whether that resolution brings in transitive dependencies. I
   recommend shading it into the plugin jar under a relocated package, which
   works either way and keeps one jar on the consumer's side.
2. Should `Said` change the runtime's prose report as well, from "missing X /
   for Y" to "Y needs X, and nothing builds it"? I recommend no. Keep the
   grouped report's layout, which lists everyone who needs X under one
   heading, and share only the one-line sentences. I'll confirm this when the
   tests are written.
3. Should `WiringGraph` be public? I recommend public but
   `@RequiresOptIn`-marked. The compiler is a separate artifact, so `internal`
   cannot reach it.
