# 0049 — A pipeline you can read

## Problem

A stuck or slow pipeline in production answers one question badly: what does
it contain? Today the answer is the source code, reached by following
`via(pipe)` calls across files. Pekko's own `toString` of a graph lists
internal stage names that do not match anything the user wrote.

Once 0046 lands, the pipeline is data, and every node carries its operator name
and build site (0010). Rendering it is a tree walk.

## Not doing

- **No live view.** This renders the description, not the running state. Per
  stage numbers are 0050, which draws them onto this diagram.
- **No layout engine.** The output is text a renderer already reads.
- **No new metadata.** A node shows what it already carries: its operator name,
  its build site, and its arguments where they are plain values.

## Shape

```kotlin
println(relay.render())             // text, one line per node
println(relay.render(Mermaid))      // a flowchart for a README or an incident doc
```

```text
tick(1s)                      Relay.kt:18
└ mapParOrFail(1)             Relay.kt:19
  └ mapConcat                 Relay.kt:20
    └ Fused[map, filter]      Relay.kt:21–22   (0047)
      └ fold                  Relay.kt:23
```

- `render` works on `Stream`, `Pipe` and `Run`, before or after 0047's pass.
  The argument `optimised = true` shows what the backend will receive.
- Fan-in (`merge`, `zip`, `concat`) renders as branches. A `Native` node shows
  as `pekko: <class of the value>`.

## Why this shape

This is pelican's move: one description, many views. Text is for logs and test
failures. Mermaid is for docs. A format of lark's own is not recommended,
because nothing reads it.

## Stack

- [ ] **`spec-0049-render`**: `render()` as text and as Mermaid, in `lark-stream`.
      Done when: a golden-file test holds the rendering of the four 0046 baseline
      pipelines, and the Mermaid output parses.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `render()` replace 0047's `explain()`?** Recommended: yes.
   `explain()` becomes `render(optimised = true)`, and one method remains.
2. **Should lambdas render as anything?** Recommended: no. The build site
   already says where to look, and a lambda's class name says nothing.

Decided while building `spec-0049-render` (2026-09-25), for editing: both
questions went as recommended. `explain()` is gone, and `render(optimised =
true)` shows what a backend compiles. A lambda renders as nothing. The build
holds each Mermaid rendering to the lines of the flowchart grammar this
renderer writes. Mermaid 11's own parser read all five goldens once, by hand,
because it needs a DOM that the build does not have. Sources have no line in a
rendering, because a source node does not carry one.
