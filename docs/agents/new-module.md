# Adding a module

Every place below has to name a new module. The ones marked *silent* fail
without a build error when missed.

| Where | What | Missed |
|---|---|---|
| `settings.gradle.kts` | `include("lark-…")` | module does not exist |
| root `build.gradle.kts`, `moduleDescriptions` | one line for the POM | silent: POM says "Part of Lark." |
| root `build.gradle.kts`, `unpublished` and the lists beside it | only if the module is not published, a Gradle plugin, or a benchmark | published by default |
| `llms.txt` and `plugins/lark/skills/lark/SKILL.md`, `## Modules` | ``- `name`: …`` | `AgentDocsTest` fails |
| the module's `NoOtherDependenciesTest` | allow-list of jar prefixes, plus the `-D…runtimeClasspath` property in its `build.gradle.kts` | no dependency gate |
| `docs/modules.md` | its family's table row | stale docs |

## A module with a lark factory

The wiring plugin and editor checker read a graph by recognising lark's
factories by package and name. A factory they do not know makes them give up on
every graph that uses it.

- `lark-app-compiler/.../Reader.kt`: add an `extension("…", "…")` entry for
  the factory. `ReaderKnowsFactoriesTest` fails when one is missing.
- `lark-app-gradle/build.gradle.kts`: `larkAppUnderTest(project(":…"))`, and a
  case in `LarkWiringPluginTest`.
- `docs/app.md`: the list of shapes the checker follows.

## A module whose docs fences compile

Fences are compiled by the module that hosts the guide's test (AGENTS.md,
Examples in the docs are compiled). Put the new module on that module's test
classpath, and its imports in the guide's fixtures fence.
