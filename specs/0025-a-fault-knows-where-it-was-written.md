# 0025 — A fault knows where it was written

## Problem

`validate` names types and not places. "missing DataSource, for OrderRepo"
leaves a grep to find the `single { }` that asked, and in a graph with two
`DataSource` consumers the grep is the whole of the work.

Two faults are not named at all. `plus` is override by definition, so a key
provided twice silently loses a node and a typo reads exactly like a deliberate
override. And a node no root reaches is built, probed and released on every
start, because nothing compares the graph against what it is started from.

## Not doing

- **No annotations and no KSP.** A module is an expression and a processor sees
  declarations; [0016](0016-an-application-that-starts-as-a-value.md) settled
  it. These checks run the expression, so they see actor nodes and conditional
  modules a processor could not.
- **No change to what `plus` means.** Override stays override.
- **Nothing that runs a recipe.** `findings` is `validate`'s neighbour, not
  `use`'s.
- **No build integration.** The task that runs this is 0026.

## Shape

```kotlin
app.findings(root = typeOf<PelicanServer>())   // List<Finding>, worst first
app.findings().report()
```

```
lark-app wiring

❯ error: missing DataSource
❯     for OrderRepo          Wiring.kt:42

❯ warning: Tracer provided twice
❯     Telemetry.kt:14        shadowed
❯     Local.kt:9             wins

❯ warning: nothing reaches KafkaProducer    Kafka.kt:9
```

- `Finding` is `Severity` plus a `WiringError`, with `Duplicate` and
  `Unreachable` joining `Missing` and `Cycle`.
- `Node` grows a `site: String?`, read by a `StackWalker` in `module`.
- Omitting `root` skips the unreachable check and nothing else.

## Why this shape

Sites come from a stack walk rather than a parameter because `single` and
`singleOf` are `inline`: the frame above `module` is already the caller's file
and line, so a node written any of the existing ways gets one for free. One
walk per node at assembly time, against a report that otherwise names a problem
without locating it.

Duplicates warn rather than fail: `overriding` is a duplicate on purpose, and
failing would mean an opt-out on every test that swaps a fake. So `overriding`
marks its replacements deliberate and is silent, and a bare `+` collision is
not. Unreachable warns for the reverse reason — a node held for its side effect
alone is legal, even though `Migrated` in `lark-app-liquibase` shows the better
answer is to make it an edge.

Unreachable names the top of each unreachable subtree rather than every node
under it. Six lines for one forgotten module is noise, and the one line is the
edit.

## Stack

- [x] **`spec-0025-sites`** — `site` on `Node`, the `StackWalker` in `module`,
      sites in `report`.
      Done when: a missing-key report names the file and line of the recipe
      that asked for it.
- [x] **`spec-0025-findings`** — `Finding`, `Severity`, shadowed nodes
      retained, `overriding` marking intent, reachability from a root.
      Done when: a key provided twice warns naming both sites, an `overriding`
      does not, and an unreachable module is named once rather than per node.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `findings` replace `validate`?** Recommend not: `validate` answers
   with a `Plan` that `use` needs, and `findings` answers with a list nobody
   starts from. Two functions over one that returns both.
2. **Should an unreachable node be suppressible in code?** Recommend not yet —
   petshop is the first graph to run this, and how many legitimate cases exist
   is a number rather than a guess.
