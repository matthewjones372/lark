# 0251 — A suite IntelliJ can run

## Problem

IntelliJ shows no run icon beside a suite object, and no "Run 'PetSuite'" in its menu. The engine (spec 0174) finds
suites when a run asks it to, but IntelliJ decides what is a test before anything runs, by reading the source. It
looks for Jupiter's `@Test` and its kin, or for JUnit's `@Testable`, which exists so a custom engine's tests can be
recognised. A suite, `object PetSuite : Suite() { init { test("…") { … } } }`, has neither: its tests are calls in
`init`, not methods. Today the owner runs the whole module or package, or `./gradlew test`, and then reruns a single
test from the results tree.

## Not doing

- **A run icon per test.** A test is a `test("…")` call, not a declaration, so no annotation can mark it. That would
  need an IntelliJ plugin, as Kotest has.
- **A per-test source line in the results tree.** Each test's descriptor still carries its suite's `ClassSource`, so
  a test opens at the top of its suite. That could be a spec of its own (open question 3).
- **Any change to the engine's discovery.** It already resolves the `ClassSelector` IntelliJ sends for a class.

## Shape

```kotlin
@Testable // org.junit.platform.commons.annotation.Testable
abstract class AnySuite<R> internal constructor(/* as today */)
```

- IntelliJ checks the whole superclass chain for `@Testable` (`JUnitUtil.isJUnit5TestClass` calls
  `MetaAnnotationUtil.isMetaAnnotatedInHierarchy`, which walks `getSupers()`). So one annotation on `AnySuite` makes
  every `Suite` and `SharedSuite` object runnable, every one already in the tree included, and users write nothing new.
- `junit-platform-commons` moves from a transitive `implementation` dependency to
  `api("org.junit.platform:junit-platform-commons")` in `core/surefold-junit/build.gradle.kts`. IntelliJ enables
  the check only when `Testable` resolves from the test module, and a consumer that uses no Jupiter may not have it
  otherwise. The runtime classpath doesn't change: `NoOtherDependenciesTest` already allows `junit-platform-commons`.
- `docs/testing.md`'s Reporting bullet says a suite gets a run icon of its own, and its tests don't.

## Why this shape

`@Testable` is the platform's documented way for an engine to tell an IDE what it runs. IntelliJ walks superclasses,
so the annotation goes on the base class and not on every suite object.

**Alternatives:**

- A `@SurefoldSuite` meta-annotation that users put on each suite. It would be explicit and wouldn't depend on how
  IntelliJ walks a hierarchy, but every suite would have to change and a new one would be silently unrunnable
  without it.
- An IntelliJ plugin, which would give each test its own run icon, but would be a project of its own.

Recommended: `@Testable` on `AnySuite`.

## Stack

- [ ] **`spec-0251-testable`** — `@Testable` on `AnySuite`, `junit-platform-commons` as `api`, and the
      `docs/testing.md` line. A test asserts that a suite object's class finds `@Testable` on a superclass.
      Done when: `./gradlew :surefold-junit:check` passes, and in IntelliJ a suite object in `petshop` or
      `surefold-junit`'s tests shows a run icon that runs only that suite.

## Acceptance

```bash
./gradlew build
```

Then, in IntelliJ: open any suite object, click the gutter icon, and see only that suite's tests in the results tree.

## Open questions

1. **IntelliJ also flags the abstract `Suite` and `SharedSuite` themselves, since its check returns true before it
   checks for an abstract class.** They're in main sources, where IntelliJ draws no test icon, so this is harmless.
   Recommended: accept it.
2. **Should `petshop` and the other consumers get a line in their docs?** No. The icon just appears once they're on
   the new version.
3. **Should each test carry a source line (`FileSource` with a line from the `test(…)` call's stack frame) so the
   results tree opens it in place?** Recommended: deferred to its own spec. It touches registration, not discovery.
