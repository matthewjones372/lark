# 0005 — Dipper comes home

## Problem

[Dipper](https://github.com/matthewjones372/dipper) is `Stream<E, A>` over
Pekko Streams: the failure in the type, no dropped element, one `Exit` with no
`else` — lark's instincts, on the streaming side. It lives in its own
repository, one spec old and unreleased, and the three things that would join
it to lark (a `Raise` in `mapOrFail`, a per-element fork on a virtual thread,
an `Exit` folded into a `Raise`) each cut across both. In two repositories
that is a version dance; in one it is a spec. The maintainer decided (chat,
2026-09-03): dipper becomes `lark-stream`.

## Not doing

- **No behaviour change.** This spec moves code; spec 0006 changes it. Every
  dipper test passes in its new home unedited but for the package line.
- **No HTTP.** A bridge to a web framework is that framework's concern.
- **No renaming of the API.** `Stream`, `Exit`, `Run`, `mapOrFail`,
  `divertLefts` keep their names; only the package and the artifact move.

## Shape

```
lark/            Arrow + JDK                                  io.github.matthewjones372.lark
lark-pekko/      + pekko-actor          (spec 0004)           io.github.matthewjones372.lark.pekko
lark-stream/     + pekko-stream         (dipper-core)         io.github.matthewjones372.lark.stream
```

- `git subtree add --prefix=lark-stream` of dipper's `main`, so its history
  arrives with it; then `dipper-core/` becomes the module root, its own
  wrapper, root build, `config/`, `AGENTS.md`, `CLAUDE.md`, `LICENSE` go
  (lark's root carries the same), and the package is renamed.
- `lark-stream/build.gradle.kts`: the Pekko BOM and `pekko-stream` as dipper
  declares them, `api(project(":lark"))`, and the classpath test with dipper's
  own allow-list plus `lark`.
- dipper's spec 0001 lands as `specs/dipper/0001-a-stream-that-names-its-failure.md`,
  its README as the **Streams** section of lark's README and `docs/stream.md`.
- The dipper repository is archived with a README pointing here.

## Why this shape

One build, one spec sequence, one release train, and a classpath test per
module so a `lark` user never sees Pekko — the discipline the family's builds
already run on. Subtree rather than a fresh copy because the commits are the
record of why the code looks as it does, which is what the spec process says
source comments do not have to be.

## Stack

- [x] **`spec-0005-import`** (`main`, c82da6d) — the subtree, the module root, the package
      rename, the build wiring, the classpath test.
      Done when: `./gradlew build` is green with three modules and every
      dipper test runs under `lark-stream`.
- [x] **`spec-0005-docs`** (`main`, 76061d5) — the README section, `docs/stream.md`, the spec
      copied, AGENTS.md's layout paragraph.
      Done when: the README's stream example compiles against the module.

## Acceptance

```bash
./gradlew build
```

## Open questions

None — decided in chat, 2026-09-03: module and package follow lark's naming;
history imported; the dipper repository archived by the maintainer.
