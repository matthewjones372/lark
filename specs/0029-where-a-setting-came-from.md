# 0029 — Where a setting came from

## Problem

A service reads `reference.conf`, then `application.conf`, then whatever the
container passes, and what comes out is one merged document. When a port is
wrong the question is always the same — which file said that, and what did it
override — and Typesafe Config answers neither from the merged value alone.

`-Dconfig.trace=loads` names the files that were loaded and the ones that were
not found. It says nothing about any particular setting. So the way this is
worked out today is by opening three files and reasoning about precedence,
which is exactly the reasoning the library already did.

## Not doing

- **No diagram.** A module graph is a DAG, so a picture says what the text
  cannot. A configuration is a tree under a fixed precedence — the same four
  boxes for every application, and a leaf per line for hundreds of lines. The
  per-setting answer is a table, and one path's override chain is three lines.
- **No change to how a service loads its configuration.** `loadedConfig()`
  stays `ConfigFactory.load()`; the layered read is a second way in, for the
  graph that wants the overrides reported.
- **No secret in any output, ever.** Values are redacted by default.
- **No new dependency**, and no reader for a format Typesafe Config does not
  already parse.

## Shape

```kotlin
config.origins()                       // List<Origin>: path, value, where it came from
config.origins().report()              // the text below
layeredConfig().origins().report()     // the same, and what each setting overrode
```

```
lark-app configuration

petshop.port                 9090          application.conf:2
                                           overrides 8080 at reference.conf:1
petshop.db.pool              16            application.conf:4
petshop.db.password          ●●●●●●        env variables
petshop.arrivalsEvery        30s           reference.conf:1
```

- `Origin` is the path, the rendered value, and where Typesafe Config says it
  came from — `file:line` for a file, `env variables` or `system properties`
  for the rest.
- A redacted value shows its origin and not its value.
- `layeredConfig()` holds the layers rather than merging them away, which is
  what lets a line say what it overrode. `loadedConfig()` cannot.

## Why this shape

Every resolved leaf already carries an origin, and it survives both merging and
substitution — so "which file won" costs nothing. What the merge destroys is
the loser: `withFallback` keeps one value and forgets the other, exactly as
`Module.plus` forgets a shadowed node. The fix is the one that worked there —
keep the layers — and it is why the override line needs `layeredConfig` and the
plain read cannot have it.

Redaction is default-deny, decided rather than asked: a report that prints a
database password into a CI log is worse than no report. The default is a name
match — `password`, `secret`, `token`, `key`, `credential` — which a caller may
extend and may not shrink. The hole in a name match is a secret that has no
such name, `jdbc:…//user:pass@host` being the common one, and the fix for that
is a `secret(path)` reader in `Reading` that marks a path redacted wherever it
is reported. Declared beats guessed; the guess is the floor.

One nuance the report must not get wrong: **an environment variable is not a
layer**. It enters only where a file wrote `${?FOO}`, and setting it with no
such substitution changes nothing. When it does win, the merged origin reads
`env variables` and loses the line that asked — which the unresolved parse
still holds, rendered as `9090,${?PETSHOP_PORT}`.

## Stack

- [ ] **`spec-0029-origins`** — `Origin`, `Config.origins()`, `report()`,
      redaction by name with the default list.
      Done when: a merged document reports every path with its file and line,
      an env-sourced value reports `env variables`, and a password reports its
      origin and not its value.
- [ ] **`spec-0029-layered`** — `layeredConfig()`, the layers kept, the
      override line.
      Done when: a setting `application.conf` overrode names the value and the
      origin it replaced, and a setting only one layer supplied says nothing
      extra.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Where does the report come out?** Recommend on demand — a value a service
   can log at start-up or a test can assert on. Not the `larkWiring` task: the
   configuration it would read is the build machine's, which is the wrong
   answer stated confidently.
2. **Does `secret(path)` land in this spec or the next?** Recommend the next.
   The name match is the floor and works on day one; the marker is a change to
   `Reading`'s surface and deserves its own argument.
3. **Should an unreadable layer fail `origins()`?** Recommend not: the report
   is a debugging tool, and refusing to explain a broken file is the opposite
   of what it is for. `config` already refuses the start.
4. **How is a redacted value rendered?** Recommend a fixed-width mask, so the
   length of a secret is not in the output either.
