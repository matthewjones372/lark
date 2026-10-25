# 0139: modules grouped by family

## Problem

The repo root holds 36 module directories side by side. Finding the stream
backends or the actor serialisers means scanning an alphabetical list where
`surefold-app-cluster` sits next to `surefold-actor-remote-avro`. The dependency graph
already has clear families (core, stream, actor, cluster, app, integrations),
but the tree does not show them.

## Not doing

- Splitting into separate repositories. One version line, one spec process.
- Renaming any module, artifact, Gradle project path or package.
- Changing a dependency between modules.
- Adding a build-logic or convention-plugin project. The root `build.gradle.kts`
  keeps configuring every module as it does now.

## Shape

```
core/          surefold  surefold-test  surefold-structured
stream/        surefold-stream  -forks  -pekko  -actors  -test  -parity
actor/         surefold-actor  -remote  -remote-avro  -remote-kotlinx
               -remote-protobuf  -journal-jdbc  -projection
cluster/       surefold-cluster  -kubernetes  -aws
app/           surefold-app  -pekko  -typesafe  -liquibase  -gradle  -compiler
               -actor  -cluster
integrations/  surefold-pekko  surefold-otel  surefold-slf4j  surefold-micrometer
               surefold-kafka  surefold-kafka-pekko
bench/         surefold-stream-benchmarks  surefold-actor-benchmarks
examples/      surefold-bank
```

`settings.gradle.kts` keeps each project path and points it at the folder:

```kotlin
fun module(group: String, name: String) {
    include(name)
    project(":$name").projectDir = file("$group/$name")
}

module("stream", "surefold-stream")
```

`:surefold-stream:test` and `io.github.matthewjones372:surefold-stream` stay exactly as
they are, so petshop and every published coordinate are untouched.

## Why this shape

Setting `projectDir` instead of including `:stream:surefold-stream` keeps project
paths flat. A nested path would rename every task, every `project(":...")`
dependency and every CI command for no gain. The alternative, splitting repos,
was weighed in chat: about one code commit in eight touches two families, and
each of those would become a coordinated release. Recommended: folders now,
revisit repos at 1.0.

## Stack

- [x] **`spec-0139-settings`** ([#406](https://github.com/matthewjones372/lark/pull/406), [#407](https://github.com/matthewjones372/lark/pull/407)): `module()` helper; core, stream, bench moved;
      `benchmarks.yml` paths and `gate.sh` paths follow.
      Done when: `./gradlew build` green and the benchmarks workflow path
      filters name the new folders.
- [x] **`spec-0139-actor-cluster`** ([#408](https://github.com/matthewjones372/lark/pull/408)): actor and cluster families moved.
      Done when: `./gradlew build` green.
- [x] **`spec-0139-app-integrations`** ([#586](https://github.com/matthewjones372/lark/pull/586)): app, integrations, examples moved;
      README, AGENTS.md "Layout", `docs/` and `llms.txt` links follow.
      Done when: `./gradlew build` green and no link in the repo names an old
      root-level module path.

Moves are `git mv`, so a reviewer sees renames, not changed lines. The cap is
about the edits around them.

## Acceptance

```bash
./gradlew build
./gradlew publishToMavenLocal   # same artifacts, same coordinates
ls                               # eight folders, no surefold-* at the root
```

Then petshop builds against the local snapshot with no change.

## Open questions

Answered with the drafted recommendations, on Matt's go-ahead to build overnight:

1. `surefold-structured` goes in `core/`.
2. `surefold-app-actor` and `surefold-app-cluster` go in `app/`.
3. `surefold-pekko` and `surefold-kafka*` go in `integrations/`; `surefold-stream-pekko` and
   `surefold-app-pekko` stay with their families.
4. `bench/` and `examples/` stay separate.
