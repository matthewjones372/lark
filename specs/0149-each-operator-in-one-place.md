# 0149: each stream operator in one place

## Problem

The facts about a stream operator are spread across files, as exhaustive
`when` blocks over the sealed `Node`:

| Fact | Where |
|---|---|
| its name | `Node.operator` (`Node.kt:278`) |
| where it was written | `Node.site` (`Node.kt:329`) |
| its rendered arguments | `label` (`Render.kt:159`) |
| whether it can fail | `canFail` (`Collapse.kt:70`) |
| its children, and rebuilding it | `children`, `withChildren` (`Node.kt:384-433`) |

Adding an operator means editing every one of those blocks, plus the backends.

Two rewrites also repeat `withChildren` by hand:

- `collapsed` (`Collapse.kt:18-50`) and `fused` (`Fuse.kt:65-90`) each rebuild
  `Merge`, `Interleave`, `ZipWith`, `Prepend`, `Concat` and `RestartOnDefect`
  case by case.
- `collapsed` keeps the "same instance if nothing changed" rule, and `fused`
  does not.

## Not doing

- Changing the backends' `when` blocks: `Pull.kt` in lark-stream-forks,
  `Compile.kt` in lark-stream-pekko, and `ActorBoundaries.kt`. Those choose an
  implementation per node, which is real dispatch, not a fact about the node.
- Changing fusion's own rules (`stepsEndingHere`, `Step`) or `Measure.isStep`.
- Adding, removing or renaming an operator.

## Shape

```kotlin
// Collapse.kt
fun Node.collapsed(): Node = withChildren(Node::collapsed).rule()

// Fuse.kt
fun Node.fused(): Node {
    val steps = (this as? Node.Unary)?.stepsEndingHere()
    return if (steps != null && steps.size >= 2) Node.Fused(steps.base.fused(), steps.steps)
    else withChildren(Node::fused)
}

// Node.kt: each fact a member, declared with the node it describes
sealed interface Node {
    val operator: String get() = javaClass.simpleName.replaceFirstChar { it.lowercase() }
    val site: String? get() = null
    val arguments: String? get() = null       // what `render` prints in brackets
    fun canFail(): Boolean                    // abstract: no default
}

data class Take(override val upstream: Node, val n: Long) : Unary {
    override val arguments get() = "$n"
    override fun canFail() = upstream.canFail()
    override fun on(upstream: Node) = copy(upstream = upstream)
}
```

`Node.operator`, `Node.site` and `Node.canFail()` keep the names they have
today, so callers in the backends and in `Measure` don't change. They move from
extensions to members.

## Why this shape

A default is safe only where getting it wrong costs nothing more than a
message:

- `operator` and `arguments` change only what a rendering prints, and `site`
  only what a message names. These three get defaults.
- `canFail` does not get one. A wrong `false` lets `collapsed` delete a
  `catchAll` that was needed, so it stays abstract: a new node without an
  answer does not compile, which is what the exhaustive `when` guaranteed
  before.

The alternative is one descriptor table per operator. That keeps the facts
together but in a third place, beside neither the node nor its callers, so I
recommend members.

## Stack

- [ ] **`spec-0149-traversal`**: `collapsed` and `fused` go through
      `withChildren`.
      Done when: `CollapseTest`, `FuseTest` and lark-stream-parity's
      `DifferentialTest` pass, and neither file names `Merge` or `Concat`.
- [ ] **`spec-0149-names`**: `operator`, `site` and `arguments` become members.
      `label`'s `when` is gone.
      Done when: `RenderTest`'s goldens and every metric tag are unchanged.
- [ ] **`spec-0149-can-fail`**: `canFail` becomes an abstract member.
      Done when: `CollapseTest` passes and `Collapse.kt` has no `when` over
      node types.

## Acceptance

```bash
./gradlew :lark-stream:test :lark-stream-parity:test :lark-stream-forks:test :lark-stream-pekko:test
```

## Open questions

1. `fused` today never keeps the same instance. After this change it will,
   wherever nothing fused. Does any backend compare nodes by identity after
   `optimised()`? I recommend checking `Compile.kt` and `ActorBoundaries.kt`
   in the first entry. I expect not, since `collapsed` already behaves this
   way.
2. Should `children` and `withChildren` become members too? I recommend not
   yet. They are structural and already live in one place. Moving them would
   double the size of the change for no extra locality.
