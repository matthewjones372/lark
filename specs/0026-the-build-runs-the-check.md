# 0026 — The build runs the check

## Problem

[0025](0025-a-fault-knows-where-it-was-written.md) gives a graph the words to
describe its own faults. Nothing says them: the check runs only if the author
remembered `app.validate().shouldBeRight()` in a test, and a service that never
wrote that test has no gate at all.

The obvious fix — every application writes its own Gradle task, or its own
test — is the per-application hand-work that makes the gate optional again. And
the unreachable check needs a root, which today exists only as a reified type
argument at the `runApp` call site, where nothing but `runApp` can read it.

## Not doing

- **No compiler plugin.** An error on the offending line while typing is the
  one thing this cannot give, and FIR is not stable enough to buy it.
- **No KSP.** The plugin finds an entry point by its declared supertype at
  build time, which is the same information for none of the machinery.
- **No change to `runApp(module) { }`.** The lambda form stays for tests and
  one-file examples.
- **Nothing published from the plugin but the plugin.** The checker's `main`
  ships in `lark-app`, so there is no second artifact to resolve.

## Shape

An application is a value that carries its own root:

```kotlin
object Petshop : LarkApp<PelicanServer>() {
    override val module: Module = settings + telemetry + theShop + arrivals + web
    override fun AppScope.run(root: PelicanServer) { root.block() }
}

fun main(): Unit = exitProcess(runApp(Petshop).code)
```

A plugin is the whole of the per-application cost:

```kotlin
plugins { id("io.github.matthewjones372.lark.wiring") }

larkWiring { unreachable = Severity.FAIL }   // optional
```

`larkWiring` runs before `check`, finds every `LarkApp` in the project's own
class output, prints `findings().report()`, fails on an error, and writes
`build/reports/lark/<app>.mmd`.

## Why this shape

`LarkApp` as an abstract class taking `typeOf<A>()` rather than an interface:
an interface cannot reify, and the root must be a `KType` the checker reads
without running `main`. The restatement is one type argument written twice on
one line, against a root the build can see.

The scan reads the project's own class output rather than its classpath, and
loads with `Class.forName(name, false, …)` so a class links without running an
initializer; only a match is initialised. ASM would be exact and is a
dependency for a job a `try`/`catch` already covers.

Loading a match does run the expression that assembles the graph — not the
recipe bodies, which `findings` never calls. A module whose assembly touches
the world would be touched by the build, which is a fault in the module under
this repo's own rule that a description is a value.

## Stack

- [x] **`spec-0026-larkapp`** — `LarkApp<A>`, `runApp(LarkApp)`, the checker
      `main` in `lark-app`.
      Done when: an application declares its root as a value, `main` is one
      line, and the checker names a fault given a class output directory.
- [x] **`spec-0026-gradle`** — `lark-app-gradle`, the `larkWiring` task and its
      extension, wiring into `check`, the `.mmd` output.
      Done when: applying the plugin alone fails a build on a missing key and
      leaves a diagram behind.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Where should the diagram land?** Recommend `build/reports/lark`, path
   configurable. A checked-in file shows an added edge in review, but a build
   that writes into the source tree by default is worse.
2. **Should the plugin apply to a project with no `LarkApp`?** Recommend the
   task registers and passes, rather than failing: a library module in an
   application build should not need to opt out.
3. **Is `lark-app-gradle` published from this repo's release?** Recommend yes,
   on the same version, though a plugin marker is a second coordinate and the
   `NoOtherDependenciesTest` rule cannot apply to a module that depends on
   `gradleApi()`.
