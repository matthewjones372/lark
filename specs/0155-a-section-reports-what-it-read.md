# 0155 — A section reports what it read

## Problem

Surefold knows everything a configuration report needs, yet every service writes it
by hand. Petshop's `main` does:

```kotlin
private val layers: Layered = layeredConfig()
object Petshop : SurefoldApp<PelicanServer>() {
    override val module: Module = configOf(layers.config) + /* … */
}
fun configReport(): String =
    layeredConfigOf(*layers.layers.map { it.name to it.config.withOnlyPath("petshop") }.toTypedArray())
        .origins(Secrets.default.and("petshop.database.url"))
        .report()
```

The path is already named by `config<Settings>("petshop")`, the secret by a
`Secret` field or `secret(path)`, and the layers by `layeredConfig()`. Each
service restates all three, and a path missed from the hand list is printed.

`layeredConfig()` also differs from `ConfigFactory.load()`: it ignores
`config.file`/`config.resource`/`config.url`, drops `application.json` and
`.properties`, and labels `CONFIG_FORCE_` env vars as system properties.

## Not doing

- **One table for the whole app.** Each section logs its own block.
- **A flag to turn it off.** The logger's level is the switch.
- **A "set but never read" warning.** Its own spec if wanted.
- **Shrinking `Secrets.default`.** The name match stays the floor.
- **Petshop.** Its migration follows once this ships (list at the end).

## Shape

```kotlin
object Petshop : SurefoldApp<PelicanServer>() {
    override val module: Module = loadedConfig() + config<Settings>("petshop") + /* … */
}
fun main() = exitProcess(runApp(Petshop).code)
```

At start, as each section is read and before anything depends on it:

```
surefold-app configuration: petshop

petshop.host            0.0.0.0     env variables
petshop.port            9090        application.conf:2
                                    overrides 8080 at reference.conf:1
petshop.database.url    ●●●●●●      env variables
```

- **Only paths read.** `Reading` records each path its leaves read; nothing
  else under the section is printed.
- **Masked by how it was read.** `secret(path)` or a `Secret` property masks
  the line, on top of `Secrets.default`.
- **Logged on refusal too.** A section that refuses the start logs what it
  did read before the refusal.
- **Layered by default.** `loadedConfig()` puts a `Layered` and its merged
  `Config` in the graph. Its layers match `load()` exactly: `CONFIG_FORCE_`
  env vars (only under `-Dconfig.override_with_env_vars`), system properties,
  the application layer (`config.file`/`resource`/`url` if set, else
  `.conf`, `.json`, `.properties`), then `defaultReferenceUnresolved()`.
  `layeredConfig()` is fixed to the same list.
- `config(path) { … }`, `choosing`, and surefold-app-kotlinx's `config<A>` all report.

## Why this shape

`Reading` is the one place every read passes through, in both forms, so the
report is a by-product rather than a second list to keep in step. Logging per
section as it is read needs no new phase in surefold-app and cannot miss a section
that refuses. The alternative, surefold-app reading every section before building
anything, would need surefold-app to recognise config nodes without depending on
Typesafe Config.

## Stack

- [x] **`spec-0155-layers`** ([#437](https://github.com/matthewjones372/lark/pull/437)): `layeredConfig()` matches `load()` layer by
      layer; `loadedConfig()` provides `Layered` and `Config`.
      Done when: the merged `Config` equals `ConfigFactory.load()` under
      `config.file`, `CONFIG_FORCE_` and `application.properties` cases.
- [x] **`spec-0155-paths-read`** ([#438](https://github.com/matthewjones372/lark/pull/438)): `Reading` records each path read and
      whether it was read as a secret, nested sections and kotlinx included.
      Done when: a class read with a `Secret` and a nested section lists
      exactly its paths, the secret marked.
- [x] **`spec-0155-report`** ([#440](https://github.com/matthewjones372/lark/pull/440)): each section logs its block when read; docs.
      Done when: a refused section logs its block, then refuses; `docs/app.md`
      and the cookbook show `main` without a report.

## Acceptance

```bash
./gradlew :surefold-app-typesafe:test :surefold-app-kotlinx:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. A section node needs the `Layered` when there is one. Should every surefold
   config source (`configOf`, `configFromFile`, `overridingConfig`) provide a
   one-layer `Layered` too, so the section always depends on it?
   **Yes.** `overridingConfig` adds an "overrides" layer on top.
2. A hand-written `single<Config> { … }` has no `Layered`. Should the section
   fall back to the merged origins, or refuse to start? **Fall back.**
3. `config.strategy` naming a custom class can only be one opaque layer.
   Acceptable? **Yes**, named after the class.

Petshop follow-up: `@Serializable Settings` with `database: Db(url: Secret)`,
`config<Settings>("petshop")`, `loadedConfig()`; delete `configReport()`,
`layers` and `configOf(layers.config)`.
