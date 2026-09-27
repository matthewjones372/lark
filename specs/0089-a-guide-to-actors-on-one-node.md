# 0089 — A guide to actors on one node

## Problem

`docs/cluster.md` (0084) starts where a second node comes in, and says so. What
comes before it has no page at all. The README's `lark-actor` row is one line
with benchmark numbers, and the rest lives in specs 0059–0067 and 0063, which
argue for each design rather than teach it:

- what a behaviour is;
- how a step answers;
- what supervision does with a raise and a throw;
- how timers, the stash and the receptionist work;
- how children are watched;
- how a persistent actor is written;
- how any of it is tested without threads.

A reader who wants "one actor that counts and restarts when it fails, and a
test for it" reconstructs it from those specs and the tests.

## Not doing

- **The cluster.** `docs/cluster.md` covers it; this page links there at its
  end.
- **Benchmarks.** `lark-actor-benchmarks/README.md` has the numbers and how
  they were taken; this page links it once.
- **Pekko migration.** A sentence where a choice differs from Pekko's, not a
  porting guide.
- **Streams on actors.** `lark-stream-actors` is a backend, and
  `docs/stream.md` owns backends.

## Shape

`docs/actors.md`, task-shaped as the cookbook and the cluster guide are:

1. **An actor** — `behaviour`, a step's answer (`stay`, `become`, `stop`,
   `unhandled`), `spawn` in a flock, `tell` and `ask`.
2. **Failure** — raise and throw, `restart` schedules, signals, children and
   `watch`.
3. **Time** — timers, receive timeouts, state-scoped timers, the flock's
   clock.
4. **Finding and routing** — the receptionist, `pool` and `group` routers,
   entities on one node.
5. **Remembering** — `persistent`, the in-memory journal, snapshots, and where
   the cluster guide takes over.
6. **Testing** — `testActors` and `.test()`: stepping without threads, dead
   letters, restarts recorded rather than waited out, `advance`.

Every complete example is a fenced block marked `<!-- actors-… -->`.
`ActorsGuideTest` in `lark-actor`'s tests compiles each one, as
`GuideExampleTest` does for the cluster guide. The testing section's examples
are also run, since a test that is shown must pass. The README's `lark-actor`
row and the cluster guide's opening link the page.

## Why this shape

It has the same shape as the cluster guide, for the same reason: a page ordered
by what a service writes, with every example compiled, stays true as the API
moves. Running the testing section's examples, rather than only compiling
them, means the page's claims about the test kit are claims the build checks.
The alternative is one page for actors and the cluster together. That is
shorter to find but twice as long to read, and a reader on one node does not
need gossip. Recommended: a page of its own.

## Stack

- [ ] **`spec-0089-actors`** — the page, `ActorsGuideTest`, and sections 1–3.
      Done when: each marked fence compiles, and one broken on purpose fails
      the test.
- [ ] **`spec-0089-more`** — sections 4–6, with the testing section's
      examples run, and the README's and cluster guide's links. Done when:
      those fences compile, the testing examples pass as tests, and the links
      are checked by the same test.

## Acceptance

```bash
./gradlew :lark-actor:test --tests '*ActorsGuideTest*'
```

## Open questions

- **A page of its own, or one page with the cluster guide?** Recommended: its
  own, as above.
- **Where does the compile test live?** Recommended: `lark-actor`'s tests,
  with the embedded compiler added in test scope. Nothing the page shows needs
  another module.
- **Run the testing section's examples, or only compile them?** Recommended:
  run them. They are tests, and showing a failing one would teach the wrong
  thing.
