# 0134 — What surefold ships, told to an agent

## Problem

An agent writing a service on surefold does not know what surefold ships, so it writes
it again. Petshop's agent hand-rolled a scheduler, a test sleep, a secret read
as a string, a `run` that never saw a SIGTERM, and a close per resource, each
something surefold already had; spec 0132 names four of those at compile time, but
only after they are written. `AGENTS.md` and `CLAUDE.md` here are for working
on surefold, and an agent in a consumer's repository never reads them. Pelican has
an `llms.txt`; surefold has nothing an agent outside this repository finds.

## Not doing

- No generated reference. Dokka and `docs/` are that; this points into them.
- No changes to the library, the checker or the docs' content.
- No MCP server.
- Pelican is not touched here; a skill for it is its own spec.

## Shape

**`llms.txt`** at the repository root, in llmstxt.org's form: what surefold is in
two sentences, the facts to have first (JDK, Kotlin, the current release,
Forks as the default backend), one line per published module saying when to
reach for it, and the need-to-API table from the cookbook's "What to reach
for", with links to `docs/`.

**A Claude Code plugin** in this repository, so a consumer installs it once:

```
/plugin marketplace add matthewjones372/lark
/plugin install surefold@surefold
```

```
.claude-plugin/marketplace.json
plugins/surefold/.claude-plugin/plugin.json
plugins/surefold/skills/surefold/SKILL.md
```

`SKILL.md`'s description triggers on surefold's group in a build file or an
`io.github.matthewjones372.surefold` import. Its body is the module catalogue, a
"before you write X, use Y" table (a scheduler, a retry, a breaker, a test
wait, a config read, a secret, a release, a migration, `run`), the four
checker warnings, and a link to `llms.txt`.

A test keeps both honest: every module `settings.gradle.kts` publishes is named
in `llms.txt` and in `SKILL.md`, and neither names one that is not there.

## Why this shape

`llms.txt` is what an agent fetches when it is pointed at a library;
the skill is what loads on its own when the code it is writing uses surefold.
Both live in this repository so a module added here is a test failure until
both say so. The alternative, one generated from the other, saves a file and
loses the voice each needs: `llms.txt` is reference, the skill is advice.

## Stack

- [ ] **`spec-0134-llms-txt`**: `llms.txt`, and the test that its modules are
      the published ones.
      Done when: removing a module line from `llms.txt` fails the test.
- [ ] **`spec-0134-skill`**: the marketplace, the plugin, `SKILL.md`, the same
      test over `SKILL.md`, and a README section on installing it.
      Done when: `claude plugin validate .` passes and the module test covers
      `SKILL.md`.

## Acceptance

```bash
./gradlew :surefold-app:test
claude plugin validate .
```

## Open questions

Answered:

1. **Where the skill lives:** here, beside `llms.txt`, so it moves with each
   release rather than in a repository of its own.
2. **Which modules count as published:** every `include` not in the root
   build's `unpublished` set (the benchmarks, `surefold-stream-parity`,
   `surefold-structured`, `surefold-bank`).
3. **The test's home:** `surefold-app`'s tests, which are already handed the
   repository root as `surefold.app.repoRoot`.
