# 0139: modules grouped by family

## Problem

The repo root holds 36 module directories side by side. Finding the stream
backends or the actor serialisers means scanning an alphabetical list where
`lark-app-cluster` sits next to `lark-actor-remote-avro`. The dependency graph
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
core/          lark  lark-test  lark-structured
stream/        lark-stream  -forks  -pekko  -actors  -test  -parity
actor/         lark-actor  -remote  -remote-avro  -remote-kotlinx
               -remote-protobuf  -journal-jdbc  -projection
cluster/       lark-cluster  -kubernetes  -aws
app/           lark-app  -pekko  -typesafe  -liquibase  -gradle  -compiler
               -actor  -cluster
integrations/  lark-pekko  lark-otel  lark-slf4j  lark-micrometer
               lark-kafka  lark-kafka-pekko
bench/         lark-stream-benchmarks  lark-actor-benchmarks
examples/      lark-bank
```

`settings.gradle.kts` keeps each project path and points it at the folder:

```kotlin
fun module(group: String, name: String) {
    include(name)
    project(":$name").projectDir = file("$group/$name")
}

module("stream", "lark-stream")
```

`:lark-stream:test` and `io.github.matthewjones372:lark-stream` stay exactly as
they are, so petshop and every published coordinate are untouched.

## Why this shape

Setting `projectDir` instead of including `:stream:lark-stream` keeps project
paths flat. A nested path would rename every task, every `project(":...")`
dependency and every CI command for no gain. The alternative, splitting repos,
was weighed in chat: about one code commit in eight touches two families, and
each of those would become a coordinated release. Recommended: folders now,
revisit repos at 1.0.

## Stack

- [ ] **`spec-0139-settings`**: `module()` helper; core, stream, bench moved;
      `benchmarks.yml` paths and `gate.sh` paths follow.
      Done when: `./gradlew build` green and the benchmarks workflow path
      filters name the new folders.
- [ ] **`spec-0139-actor-cluster`**: actor and cluster families moved.
      Done when: `./gradlew build` green.
- [ ] **`spec-0139-app-integrations`**: app, integrations, examples moved;
      README, AGENTS.md "Layout", `docs/` and `llms.txt` links follow.
      Done when: `./gradlew build` green and no link in the repo names an old
      root-level module path.

Moves are `git mv`, so a reviewer sees renames, not changed lines. The cap is
about the edits around them.

## Acceptance

```bash
./gradlew build
./gradlew publishToMavenLocal   # same artifacts, same coordinates
ls                               # eight folders, no lark-* at the root
```

Then petshop builds against the local snapshot with no change.

## Open questions

Answered with the drafted recommendations, on Matt's go-ahead to build overnight:

1. `lark-structured` goes in `core/`.
2. `lark-app-actor` and `lark-app-cluster` go in `app/`.
3. `lark-pekko` and `lark-kafka*` go in `integrations/`; `lark-stream-pekko` and
   `lark-app-pekko` stay with their families.
4. `bench/` and `examples/` stay separate.
