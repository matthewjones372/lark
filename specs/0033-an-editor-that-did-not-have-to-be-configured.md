# 0033 — An editor that did not have to be configured

## Problem

[0027](0027-a-fault-while-you-type.md) built a FIR checker so a wiring fault is
a red underline rather than a build failure, and
[0030](0030-nothing-reaches-this-node.md) and
[0031](0031-two-recipes-and-a-ring.md) taught it three more faults. All four
work. Almost nobody sees them.

IntelliJ's `KtCompilerPluginsProviderIdeImpl` reads the registry key
`kotlin.k2.only.bundled.compiler.plugins.enabled`, which ships `true`, and runs
only the bundled plugins. Everything else is loaded into the model and ignored.
So the offer today is "apply the plugin, and have everyone on the project change
an IDE setting they will not find" — and 0027 said in its own open question 5
that the answer which would kill the spec is `larkWiring` already failing the
build on the same fault, for everyone, with no setting to change.

That is where this sits. The compiler half earns its keep: `compileKotlin`
reports every fault, in CI and on a laptop. The editor half is off by default
and there is no way for this repository to turn it on.

## Not doing

- **No second reader.** The Analysis API would let an inspection walk the graph
  in the IDE's own process, and it would be a second implementation of
  `Reader.kt` that can disagree with the first. 0027's open question 4 asked for
  a test that they cannot; two readers is that question with no good answer.
- **No new diagnostics.** The four are the four.
- **No change to the compiler plugin**, which stays the thing CI runs.

## Shape

An IntelliJ plugin that runs the checker that already exists, out of process,
against what the module last compiled.

```
Wiring.kt
  92 │  actor<Shop>("shop") { shop() }
     │  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     │  lark-app: nothing reaches ActorRef<Shop>, and it is built on every start
     │  from the last build of :app — rebuild to refresh
```

- `checkWiring(classes, sources = …)` is already a function and already prints
  `e: file://…:line:col message`, which is the shape 0027 chose so a build's
  output could be parsed. The plugin forks a JVM, reads that, and turns each
  line into an annotation.
- **The answer is dated, and says so.** A finding from a build two edits ago is
  not a lie only if it admits what it is.
- No registry key, no `-Xplugin`, nothing for a reader to configure beyond
  installing it.

## Why this shape

0027 recommended against exactly this, and the reason it gave was right: an IDE
plugin needs installing, publishing, and a second implementation. Two of those
three still hold. The third does not — an external annotator that shells out to
`checkWiring` has no second implementation, because it runs the same code
`larkWiring` runs, on the same class files, and cannot disagree with it.

What changed is the evidence. 0027 shipped the editor half and then found the
registry key, so the feature is present and unreachable. A plugin that needs
installing is a worse story than one that needs nothing — and a better one than
a setting nobody knows to change.

The cost worth stating plainly is a second published artefact with a second
release cadence, on the JetBrains Marketplace rather than Central, built against
an IntelliJ SDK that moves every quarter. That is a larger standing cost than
`kotlin-compiler-embeddable`, which at least only breaks on Kotlin upgrades.

The alternative worth considering is a plugin whose only job is to set that
registry key to `false` on startup, which is perhaps thirty lines and makes the
existing checker run with no annotator at all. It changes a global IDE setting
on the reader's behalf, for every project they open and every third-party
compiler plugin they have. Recommend not doing it silently; it may be worth
doing as an offer the reader accepts.

## Stack

- [ ] **`spec-0033-the-plugin-shell`** — `lark-idea`: an IntelliJ plugin module,
      `plugin.xml`, and an annotator registered for Kotlin files that reports
      nothing.
      Done when: the plugin builds, loads in a sandbox IDE, and a log line
      proves the annotator ran on `Wiring.kt`.
- [ ] **`spec-0033-findings-from-a-build`** — fork a JVM running `checkWiring`
      against the module's output and source roots, parse its lines, annotate.
      Done when: deleting `actor<Shop>("shop")` from petshop underlines the
      recipe that asked, with the wording `larkWiring` prints.
- [ ] **`spec-0033-when-it-is-stale`** — the date of the answer on every
      annotation, and nothing at all where the module has never been compiled.
      Done when: an edit that has not been built says so rather than reporting
      the previous graph as though it were this one.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Is this worth a second artefact at all?** The honest alternative is to do
   nothing and document the registry key in the README, which costs one
   paragraph and no release cadence. Recommend deciding this before the first
   entry, not after the third — 0027 learned that lesson the expensive way.
2. **Marketplace, or a zip?** Recommend a zip from the releases page first: it
   is the same install for a team and it does not commit to a review queue.
3. **Which IntelliJ versions?** Recommend the current stable only, no range,
   for the reason 0027 pins one Kotlin version.
4. **Where do the class files come from?** The IDE knows a module's output path,
   but a Gradle project that has never been built has none. Recommend silence
   there, and see entry three.
5. **How often does it run?** On file open and on build completion, not on every
   keystroke — it is a forked JVM reading a directory of class files.
6. **Does the registry-key plugin make the rest of this unnecessary?** Possibly,
   and it is thirty lines against three branches. Recommend trying it first and
   building this only if that is judged too invasive.
