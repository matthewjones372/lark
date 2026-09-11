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
  on FIR and emits no IR needs nothing installed — but see **What the editor
  costs** below, because it is not free.
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

## What the editor costs

Two things found while building the first entry, both of which a reader should
know before deciding the other two are worth it.

**The editor runs no third-party checker unless it is told to.** IntelliJ's
`KtCompilerPluginsProviderIdeImpl` reads the registry key
`kotlin.k2.only.bundled.compiler.plugins.enabled`, which ships `true`, and runs
only `KotlinK2BundledCompilerPlugins` — all-open, no-arg, sam-with-receiver,
assignment, serialization, Lombok, Parcelize, Compose, scripting,
js-plain-objects, dataframe. Everything else is loaded into the model and
ignored. With the key unchecked the checker runs and reports, confirmed against
petshop; so the offer is not "apply the plugin", it is "apply the plugin and
have everyone on the project change an IDE setting".

**The editor fails quietly, and the compiler does not.** Three times over, in one
sitting: a relocated `PsiElement` the compiler has and the editor does not; a
positioning strategy that casts its source to a `KtDeclaration`, which the
compiler tolerates on a call and the editor answers by dropping the diagnostic;
and a republished `SNAPSHOT`, which the compiler picks up and the editor serves
from a cached classloader until the version changes. Each looked like "nothing
appears", each was a different cause, and in every one the build was green and
the editor silent. "It works from the CLI" is not evidence about the half this
spec exists for.

**A checker that runs in the editor cannot use the reified diagnostic DSL.**
`kotlin-compiler-embeddable` relocates IntelliJ's classes under its own package
and the IDE runs the compiler unrelocated, so `warning1<PsiElement, _>()` bakes
a name into the plugin's bytecode that exists in only one of the two. The
factories are built through their non-inline constructors with the `KClass`
resolved by name instead. Any FIR API taking a reified PSI type is closed the
same way, and the failure is quiet: an exception inside a checker takes the
file's remaining analysis with it, so the symptom is unrelated type errors on
code that compiles.

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

- [x] **`spec-0027-plugin-shell`** ([#49](https://github.com/matthewjones372/lark/pull/49)) — `lark-app-compiler`:
      registrar, FIR extension, a checker that finds `LarkApp` subclasses and
      reports nothing. `lark-app-gradle` becomes a
      `KotlinCompilerPluginSupportPlugin`.
      Done when: a TestKit build proves the extension ran, and the editor shows
      the same warning. Both hold; what it cost is above.
- [x] **`spec-0027-reading-the-graph`** ([#50](https://github.com/matthewjones372/lark/pull/50)) — the FIR reader
      above, and the give-up rule.
      Done when: petshop's graph reads to the same key set `Module.nodes` holds
      at runtime. It does — the same ten keys, and the same one short.
- [x] **`spec-0027-the-diagnostic`** ([#51](https://github.com/matthewjones372/lark/pull/51)) — a missing-key
      `KtDiagnosticFactory` on the call that asked, wording shared with
      `Diagnostics.kt`. Cycles and duplicates stay the task's.
      Done when: deleting `actor<Shop>("shop")` from petshop fails
      `compileKotlin` naming `Wiring.kt:92`, and a given-up graph compiles clean.
      Both hold, and the editor underlines the same call.

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
5. **Is an editor behind a registry key worth two more branches?** Answered by
   building them: it works, and the three quiet failures above are the running
   cost to expect on every Kotlin upgrade. The
   answer that would kill this spec is that `larkWiring` already fails the build
   on the same fault, with a line the IDE links, for everyone and with no
   setting to change. Recommend deciding it before the second entry rather than
   after the third.
