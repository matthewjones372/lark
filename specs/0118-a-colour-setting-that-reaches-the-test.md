# 0118 — A colour setting that reaches the test

## Problem

[0116](0116-a-story-in-colour.md) colours a story's console copy when
`FORCE_COLOR` is set, turns it off for `NO_COLOR`, and takes
`-Dlark.test.colour` over both. From the command line, none of the three
arrives. `FORCE_COLOR=1 ./gradlew test` sets nothing in the JVM the tests run
in, because a Gradle test worker does not inherit the shell's environment, and
`-Dlark.test.colour=always` on the command line sets a property of Gradle's own
JVM, not the worker's.

Every build that wants it pastes the same five lines from `story`'s KDoc into
`tasks.test`. The petshop has them in `app/build.gradle.kts`. A build without
them gets no colour from the command line, which looks the same as colour being
off, so nobody can tell the setting was lost.

## Not doing

- Colour decisions. The five rules stay 0116's; this only carries their inputs.
- Detecting a terminal. A worker still has no console to ask.
- Any other environment variable. Two names and one property, no general
  pass-through.

## Shape

Applying the plugin a service applies already is enough:

```kotlin
plugins { id("io.github.matthewjones372.lark.wiring") }
```

```bash
FORCE_COLOR=1 ./gradlew test                    # coloured
NO_COLOR=1 ./gradlew test                       # plain
./gradlew test -Plark.test.colour=always        # coloured, whatever the environment says
```

- Every `Test` task in the project gets `FORCE_COLOR` and `NO_COLOR` from the
  environment Gradle was started in, when they are set, and
  `lark.test.colour` as a system property from the Gradle property of that
  name.
- They are declared as the task's inputs, so changing one reruns the tests
  rather than serving a coloured run from the cache as plain, or the reverse.
- Nothing is set when none of them is, so a build that does not ask gets exactly
  what it gets today.
- `story`'s KDoc loses its snippet and names the plugin instead.

## Why this shape

The plugin is already applied by any service that checks its graph, so the fix
costs a user nothing. The alternative is a plugin of its own,
`io.github.matthewjones372.lark.test`, applied by a service that uses `lark-test`
without `lark-app`. That is cleaner, but it is a second plugin for five lines.
Recommended: the wiring plugin now, and a separate one only if a service
without `lark-app` asks.

## Stack

- [x] **`spec-0118-colour-through-gradle`** (#344) — the wiring plugin configures every
      `Test` task, and `story`'s KDoc points at it.
      Done when: a TestKit build applying the plugin, run with `FORCE_COLOR=1`,
      hands the worker `FORCE_COLOR=1`; with `-Plark.test.colour=always` the
      worker reads that property; with neither, the worker sees no
      `FORCE_COLOR` and no `lark.test.colour`; and changing `FORCE_COLOR`
      between two runs reruns the test task.

## Acceptance

```bash
./gradlew :lark-app-gradle:test
```

Then the petshop drops the block from `app/build.gradle.kts`, and
`FORCE_COLOR=1 ./gradlew :app:test` still prints a coloured story.

## Open questions

- **The wiring plugin, or a `lark-test` plugin?** Recommended: the wiring
  plugin, as above.
- **Only test tasks whose classpath holds `lark-test`?** Narrower, but it means
  resolving the classpath at configuration time. Recommended: every `Test`
  task; the variables are harmless where nothing reads them.
- **Is an environment variable a cache key?** Gradle does not track a worker's
  environment as an input unless told. Recommended: declare both as inputs, as
  above.
