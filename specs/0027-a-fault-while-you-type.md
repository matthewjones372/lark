# 0027 — A fault while you type

## Problem

[0026](0026-the-build-runs-the-check.md) put the wiring check on the build, and
[#48](https://github.com/matthewjones372/lark/pull/48) moved it to `classes` and
gave it the compiler's `e: file://…:line:col` shape, so a missing key is a
clickable entry in the Build window. That is as far as a task can go: the check
is a JVM that runs the graph, and nothing runs it until something compiles.

Missing is what the DSL keeps implying it has. Someone who writes
`single { shop: PetShop -> … }` and provides no `PetShop` has written a type
error in everything but the compiler's opinion, and the editor says nothing
until a build. ZIO's `provide` macro reports it as you type, and that is the
comparison this library invites.

## Not doing

- **No IntelliJ plugin.** K2 mode loads third-party compiler plugins from the
  Gradle model and runs their FIR checkers in the editor. A checker that stays
  on FIR and emits no IR needs nothing installed.
- **No replacement for `larkWiring`.** The task stays the gate, because it sees
  the graph by running it.
- **No cross-module analysis.** A `Module` from another Gradle module is a
  compiled symbol with no initialiser to read.
- **No new DSL.** Nothing changes to be easier to read statically.

## Shape

One apply, and the graph is analysed as it is typed.

```kotlin
object Petshop : LarkApp<PelicanServer>() {
    override val module: Module =
        singleOf(::ActorPetShop).boundTo<PetShop>() + web
    //  ~~~~~~~~~~~~~~~~~~~~~~~
    //  lark-app: PetShop needs ActorRef<Shop>, and nothing builds it
}
```

What the reader takes out of the FIR tree, all within one Gradle module:

- `single<A> {}`, `single { d: D -> }`, `singleOf(::Ctor)`, `actor<P>("n")`,
  `config<T>(path) {}` — reified arguments are explicit after resolution
- `boundTo<B>()`, `overriding()`, `probe()`, `subgraph<A>()`
- `+` chains, and references to top-level `val`s and functions in the same unit
- `if` and `when` branches, unioned: a key is missing only where every branch
  is missing it

Anything else — a loop, a collection, an interface method, another Gradle
module — makes the application unanalysable and the checker emits nothing.

## Why this shape

The rule that decides whether this is worth having is **give up quietly**. A
false positive in an editor is worse than no editor support, because the reader
learns to ignore the colour; a false negative costs nothing, because
`larkWiring` fails the build thirty seconds later. So an unknown sub-expression
abandons the application rather than guessing.

The alternative is an IntelliJ plugin with an external annotator shelling out to
the existing checker. It would see the whole graph, and it would need
installing, publishing, and a second implementation. Recommend the compiler
plugin: it also gives a plain `compileKotlin` the error, which no IDE plugin
can.

The cost worth stating is `kotlin-compiler-embeddable`, which has no stable API.
FIR checker signatures move between Kotlin minors, and this module will break on
upgrades nothing else here notices. Hence one module that can be dropped, rather
than something `lark-app` depends on.

## Stack

- [ ] **`spec-0027-plugin-shell`** — `lark-app-compiler`: registrar, FIR
      extension, a checker that finds `LarkApp` subclasses and reports nothing.
      `lark-app-gradle` becomes a `KotlinCompilerPluginSupportPlugin`.
      Done when: a TestKit build proves the extension ran, and one screenshot
      proves K2 mode loads it without an IDE plugin.
- [ ] **`spec-0027-reading-the-graph`** — the FIR reader above, and the give-up
      rule.
      Done when: petshop's graph reads to the same key set `Module.nodes` holds
      at runtime, asserted against it.
- [ ] **`spec-0027-the-diagnostic`** — a missing-key `KtDiagnosticFactory` on
      the call that asked, wording shared with `Diagnostics.kt`. Cycles and
      duplicates stay the task's.
      Done when: deleting `actor<Shop>("shop")` from petshop fails
      `compileKotlin` naming `Wiring.kt:92`, and a given-up graph compiles clean.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Which Kotlin versions does it promise?** Recommend the one this repo
   builds with, no range: a range is a test matrix against an unstable API.
2. **Own artifact, or inside `lark-app-gradle`?** Recommend its own: one jar for
   both drags `gradleApi()` into the compiler's classloader.
3. **Report the unreachable-node warning too?** Recommend no, first pass — it
   depends on the root, which is the part FIR can least confirm.
4. **What if the checker and `larkWiring` disagree?** Recommend a test that they
   cannot, in the second entry. If it is hard to write, the reader is too clever
   and should give up sooner.
