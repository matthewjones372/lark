# 0084 — A guide to running a cluster

## Problem

Specs 0059 to 0083 built actors that cross nodes, a cluster with sharding and
singletons, persistent entities on JDBC with snapshots and pruning, read
models, reliable delivery, graceful stopping, metrics, topics and roles. None
of it has a page a service author would read. `docs/` covers `lark-app`, the
streams and Kafka. The README's module table has one line per module and links
to specs, which argue for a change rather than teach it. A reader who wants
"three nodes, orders sharded across them, remembered in Postgres" today has to
reconstruct it from a dozen specs and the tests.

## Not doing

- **Reference for every parameter.** KDoc covers the signatures; the guide
  shows the calls a service writes and says why, and links out.
- **An example application in `example/`.** The guide's examples compile, as
  `docs/stream.md`'s do; a runnable service is its own piece of work if asked.
- **Pekko migration notes.** A comparison where it explains a choice, not a
  porting guide.
- **Documenting `lark-actor` on one node.** Behaviours, supervision, timers
  and `testActors` get their own page later; this one starts where a second
  node comes in, and links back to the README for the rest.

## Shape

`docs/cluster.md`, task-shaped as `docs/cookbook.md` is, in the order a
service meets things:

1. **Two nodes** — `node(…)`, `expose`, `remote`, codecs, TLS.
2. **A cluster** — seeds and discovery, `View`, member events, downing,
   `Cluster.ready()`.
3. **Entities** — `sharding`, `entity(id)`, passivation, singletons, roles.
4. **State that survives** — `persistent`, `JdbcJournal` and its DDL,
   snapshots, pruning, read models with `Projection.follow`.
5. **Commands that must arrive** — `delivered`, `reliable`, `drain`.
6. **Stopping and watching** — leaving on close, `leaveWithin`, the metrics
   and what each one tells an operator, topics.

Every complete example is a fenced block marked `<!-- cluster-… -->`, with its
imports written out as AGENTS.md asks. `GuideExampleTest` in `lark-cluster`
compiles each marked fence with the embedded Kotlin compiler, as
`ReadmeExampleTest` does for the streams page, so an API change that breaks
the guide fails the build. The README's module table links to the page.

## Why this shape

A page ordered by the questions a service asks, with every example compiled,
is what `docs/stream.md` and the cookbook already are, and it keeps the guide
honest as the API moves. The alternative is a runnable example project, which
proves more end to end but is a second codebase to keep building, and the
module tests already prove the behaviour. Recommended: a compiled guide.

## Stack

- [x] **`spec-0084-nodes`** — the page, `GuideExampleTest`, and sections 1–3.
      Done when: each marked fence compiles in the test, and one fence broken
      on purpose fails it.
      ([#228](https://github.com/matthewjones372/lark/pull/228))
- [x] **`spec-0084-state`** — sections 4 and 5. Done when: their fences
      compile, including the Postgres DDL's path as the jar ships it.
      ([#229](https://github.com/matthewjones372/lark/pull/229))
- [x] **`spec-0084-operating`** — section 6, and the README's link. Done when:
      its fences compile, and the README's module table links the page from
      the cluster, remote and journal rows.
      ([#230](https://github.com/matthewjones372/lark/pull/230))

## Acceptance

```bash
./gradlew :lark-cluster:test --tests '*GuideExampleTest*'
```

## Open questions

- **Compiled examples only, or also a runnable project?** Recommended:
  compiled only, as above.
- **Where does the compile test live?** Recommended: `lark-cluster`'s tests,
  which already have every module the guide uses on their classpath; the
  embedded compiler is added there in test scope, as `lark-stream-pekko` has
  it. The examples take a `DataSource`, so no database driver is needed to
  compile them.
- **One page or several?** Recommended: one, with a contents list; it is
  read top to bottom the first time and searched afterwards.

Decided (2026-09-27): every open question goes as recommended. The guide's
examples are compiled, with no runnable project; the compile test lives in
`lark-cluster`'s tests; and the guide is one page with a contents list.

Decided while building `spec-0084-nodes`: each example is an extension on the
service's own `Flock<Nothing>`, so it compiles alone and says nothing about
where the flock comes from, which `flock { }` and `Actors.within` answer
differently. The compiler is `lark-stream-pekko`'s `EmbeddedKotlin`, copied
into `lark-cluster`'s tests, and the page is an input of the test task, so an
edit to it reruns the test. The test also compiles one example with a call
renamed and expects errors, so a harness that compiled nothing would fail.

Decided while building `spec-0084-state`: `lark-cluster`'s tests take
`lark-actor-projection` and `lark-stream-forks`, so the read model's example
compiles with the rest. The DDL paths are checked rather than compiled: the
test reads every `lark/journal/jdbc/….sql` the page names and finds each on the
journal's classpath, so renaming a file without the page fails it.

Decided while building `spec-0084-operating`: the last section is "stopping,
watching and telling everyone", so topics sit with the other things an
operator meets once a cluster runs. The metrics are a table of what each tells
an operator, not only what it counts. The README's links are held by the same
test, which reads the three rows and finds the page in each, and the README is
an input of the test task beside the page. `lark-cluster`'s tests take
`lark-app-actor` for the example that wires a cluster into an application;
that module takes `lark-cluster` only in its own tests, so nothing is circular.
