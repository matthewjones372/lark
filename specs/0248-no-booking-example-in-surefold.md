# 0248 — No booking example in Surefold

## Problem

`examples/surefold-bookings` (specs 0229 and 0232) is a small booking service: courts, multi-slot bookings, events and
a per-member weekly limit, built on surefold-auth, surefold-tenancy, surefold-jobs and surefold-idempotency. Booking
is not Surefold's domain: it is a library of concurrency, application graphs, data and integration modules, and a
booking product built on it already exists outside the tree, which does all of this and more. Keeping a
second, smaller booking service here makes Surefold look like it ships booking logic, and every change to the data
modules has to keep it compiling. The owner, 2026-10-28: "why is there a surefold booking module?! remove that".

## Not doing

- **Moving it anywhere.** The booking product outside the tree already books courts, holds several slots
  together, takes staff bookings and limits what one person may hold; nothing in the example is missing there.
- **Changing any `surefold-*` module.** The example depended on them; nothing depends on it.
- **Rewriting specs 0229 and 0232.** Shipped specs stay in the tree as the record; each gets one line saying this spec
  removed what it built.
- **A replacement example of the data modules together.** Each module's guide and tests stay; a combined example can
  be its own spec if one is wanted.

## Shape

The module is gone: no `examples/surefold-bookings` directory, no `module("examples", "surefold-bookings")` in
`settings.gradle.kts`, no entry in the root build's set of unpublished modules, and no row or section for it in
`README.md` or `docs/modules.md`.

```bash
./gradlew :surefold-bookings:build   # fails: project 'surefold-bookings' not found
```

## Why this shape

Deleting it outright is simpler than keeping it as an unpublished example: it is not a dependency of anything, its
tests exercise modules that keep their own suites, and a booking service is product code. The alternative, keeping it
and renaming it as a generic "data modules together" example, is not recommended: it would still be a booking service
in everything but name.

## Stack

- [ ] **`claude/youthful-faraday-ydey6e`** — this spec, then the module, its build and docs entries removed, and a
      line on specs 0229 and 0232.
      Done when: `./gradlew build` passes and nothing outside `specs/` names `surefold-bookings`.

## Acceptance

```bash
./gradlew build
grep -rn 'surefold-bookings' --exclude-dir=specs --exclude-dir=build .   # no matches
```

## Open questions

None: the owner asked for it to be removed.
