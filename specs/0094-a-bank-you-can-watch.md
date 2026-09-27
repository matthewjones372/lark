# 0094 — A bank you can watch

## Problem

Everything 0059–0085 built is proved by tests and described in a guide, and
none of it has been used together by something that looks like a service. No
one has sent money between two accounts sharded across three nodes, through a
page in a browser, while another page shows what the cluster is doing. The
questions only such an application raises have not been asked:
- does a transfer that touches two entities on two nodes stay correct when a
  node dies;
- can an operator see that happen, live, without a metrics stack beside it;
- what does it take to serve lark over HTTP at all?

A reader choosing lark has the guides and nothing to run.

## Not doing

- **A real bank.** No authentication, no currencies, no interest, no limits
  beyond "the balance cannot go below zero". An account is picked by id on the
  page.
- **An HTTP library in lark.** The server is the JDK's
  `com.sun.net.httpserver`, and it lives in the example, not in a published
  module. A general `lark-http` is a spec of its own if this one shows it is
  wanted.
- **A frontend build.** Plain HTML, CSS and JavaScript served from resources:
  no npm, no bundler, no framework. Charts are drawn on a `<canvas>` by hand.
- **Grafana, Prometheus or OpenTelemetry.** The admin page reads lark's own
  metrics, streamed from the nodes; exporting them elsewhere is
  `lark-micrometer`'s and `lark-otel`'s already.
- **Publishing.** `lark-bank` is built and tested with the rest and never
  published, like the benchmarks.

## Shape

A new module, `lark-bank`: an application you run.

```bash
./gradlew :lark-bank:run          # three nodes in one JVM, on ports 8081–8083
```

**The platform.** Three cluster nodes (0069, 0073) with H2 in memory as their
journal (0072), or Postgres when `--jdbc` is given.
- **`Account`,** a persistent sharded entity (0070): `Opened`, `Debited`,
  `Credited`, `Refused`. A debit that would go below zero is refused.
- **`Transfer`,** a persistent sharded entity that drives one transfer as a
  saga: `Requested → Debited → Credited`, or `Refused`. It sends `Debit` to the
  source account and then `Credit` to the destination, each through a durable
  reliable producer (0079, 0085), so a node that dies mid-transfer delays it
  and never loses or doubles money.

**The consumer page** (`/`), on any node:
- pick or open an account, and see its balance and its last 50 movements;
- send money: `POST /api/transfers {from, to, amount}` answers `202` with the
  transfer's id, and the page follows it until `Done` or `Refused`.

**The admin page** (`/admin`), on any node, fed by one SSE stream,
`GET /admin/stream`:

```
event: stats
id: 1843
data: {"node":"n2","at":"…","transfersPerSecond":212,"refused":3,"p99Ms":41,
       "shards":34,"entities":1209,"unconfirmed":7,"deadLetters":0}

event: member
data: {"node":"n3","status":"Unreachable"}

event: transfer
data: {"id":"t-9f2…","from":"a-12","to":"a-40","amount":250,"outcome":"Done","ms":18}
```

- **Streamed from the platform, not polled by the page.** Each node runs a
  sampler actor that reads its own flock's metrics (0081) once a second and
  publishes a `NodeStats` to the cluster topic `bank-stats` (0082). Membership
  changes and finished transfers go to the topic `bank-events`. Every node
  keeps a dashboard actor subscribed to both, so an admin connected to any one
  node sees all three.
- **One SSE connection, one virtual thread.** The server's executor is virtual
  threads. Each connection has a bounded queue; a slow browser loses older
  `stats` (the newest supersedes them) before it loses anything else, and a
  queue full for 10 s closes the connection. A comment line every 15 s keeps
  proxies from closing an idle stream. A write that fails unsubscribes the
  connection.
- **The page shows:** tiles for the cluster's transfers a second, refusals and
  p99; a table per node with status, shards, entities and unconfirmed
  deliveries; a sparkline of the last five minutes; and the live transfer
  feed.

**A load button** on the admin page starts and stops a generator of random
transfers, and **"crash n3"** kills a node, so the reader can watch shards move
and deliveries resend without writing a line.

## Why this shape

SSE over WebSockets: the admin stream only flows from server to browser,
`EventSource` reconnects by itself, and SSE is plain HTTP that the JDK server
can write with no library. The alternative, a WebSocket, needs an upgrade the
JDK server does not do, and a library to do it. Stats travel over a cluster
topic rather than the page asking each node, because that is the pattern a
real service would use and it exercises 0082; the alternative, the admin node
scraping the others over HTTP, is simpler but shows nothing of lark.
Recommended: SSE, fed by topics.

## Stack

- [x] **`spec-0094-bank`** — the module, `Account` and `Transfer`, and three
      nodes started in one JVM, without HTTP. Done when: 1,000 random
      transfers across three nodes, with one node crashed midway, end with
      every transfer `Done` or `Refused` and the total money unchanged.
- [x] **`spec-0094-api`** — the JDK server on virtual threads and the
      consumer's JSON API. Done when: a test with `java.net.http.HttpClient`
      opens two accounts, moves money between them on different nodes, reads
      both balances, and gets `Refused` for an overdraft.
- [x] **`spec-0094-consumer`** — the consumer page. Done when: it is served
      from `/`, and a headless Chromium test sends money through the page and
      sees the new balance.
- [ ] **`spec-0094-sse`** — SSE framing, heartbeats, the bounded queue per
      connection, and closing on failure. Done when: tests show the event
      format, a slow client losing old `stats` and keeping `member` events,
      and a closed client unsubscribed.
- [ ] **`spec-0094-stats`** — the sampler, the dashboard actor, both topics
      and `/admin/stream`. Done when: a client of node 1's stream reads
      `stats` from all three nodes, then a `member` event naming n3 once n3 is
      crashed.
- [ ] **`spec-0094-admin`** — the admin page, the load button and "crash n3",
      and a README row. Done when: a headless Chromium test starts the load,
      sees the transfers-a-second tile rise above zero, crashes n3, and sees
      its row turn unreachable.

## Acceptance

```bash
./gradlew :lark-bank:build
./gradlew :lark-bank:run    # then open http://localhost:8081 and /admin
```

## Open questions

- **Which HTTP server?** Recommended: the JDK's `HttpServer` on a
  virtual-thread executor, with no library. The alternatives are Ktor or
  Javalin, which are nicer to route with but bring a second runtime that the
  reader has to see past.
- **JSON: a library, or hand-written?** Recommended: a small hand-written
  encoder and parser in the example for the half-dozen flat shapes it sends.
  `kotlinx.serialization` is the alternative if the shapes grow.
- **Does the consumer page get SSE too,** so a balance moves as money arrives
  from someone else? Recommended: not in this spec; the page follows its own
  transfer by asking. It is one more stream on the same machinery, and a small
  spec after this one if wanted.
- **Browser tests: Playwright, or HTTP tests only?** Recommended: Playwright
  for Java against the Chromium the environment already has, two tests in all
  (the consumer and admin entries). They are the only proof the pages work.
- **One JVM or three processes?** Recommended: one JVM with three nodes for
  `run`, as the tests do, and a `--node n2 --seed 8081` form for running them
  as separate processes.

Decided (2026-09-27): every open question goes as recommended. The server is
the JDK's `HttpServer` on virtual threads, with no library; JSON is
hand-written for the example's few flat shapes; the consumer page follows its
own transfers by asking, with no stream of its own yet; the two page tests use
Playwright for Java against the Chromium the environment has; and `run` starts
three nodes in one JVM, with a form for running them as separate processes.

Decided while building `spec-0094-bank`:
- **Split in two.** `spec-0094-bank` is the module and the two entities, proved on `testActors`; `spec-0094-bank-cluster` is three nodes, the producers and the crash. The entry's done-when is proved, and the entry ticked, on the second.
- **Past the soft cap.** About 550 lines: two hand-written wire codecs and two event codecs are half of it, and the tests most of the rest.
- **Not `delivered`.** A persistent entity wrapped in `delivered` drops a resent command without a step, so what that step sent on is lost if the node crashed between writing the events and sending. `repeatable` hands each entity every copy, unwrapped, and confirms it once the step returns.
- **A repeat is answered from state.** An account keeps every transfer id it has debited, refused or credited, and answers a repeat the same way without writing. A transfer that hears a start or an answer again sends on again whatever the first sent.
- **What the tests catch.** An account that debits a repeat again, or that does not refuse an overdraft, fails them.

Decided while building `spec-0094-bank-cluster`:
- **Producers by incarnation.** Each node's two durable producers are named by its name and its member uid, so a node started again under the same name never shares a producer with its earlier life.
- **Adopted on removal.** When a member is removed, the oldest `Up` member left starts that member's producers again, and they send what it kept. A node that is closing adopts nothing.
- **Waiting without `Cluster.await`.** That is internal to `lark-cluster`, so each node keeps its own view from its `MemberEvent`s, with a condition to wait on.
- **Waiting for transfers.** An ask can be dead-lettered while a crashed node's shards move, so the test does not ask each transfer how it ended. Each node reports every transfer that ends, and the test waits on a blocking queue until all 1,000 have. A repeated answer reports the end again, so a report lost to a crash is made again.
- **The crash bites.** One sender per node runs at once, and n3 goes halfway through its share. Without adoption, transfers never end, and the test fails. An account that debits a repeat again ends with 1,713 of the 20,000.
- **Postgres is cheap.** `--jdbc URL` takes a Postgres or H2 URL, and creates the journal's tables if they are missing. The Postgres driver is one jar on the classpath, and embedded Postgres is used only in the tests. `--node n2 --jdbc URL` runs one node alone. The cluster ports are 25521 to 25523.
- **Found in the library.** A node closing sometimes logs a `ConcurrentModificationException` from `Flock`'s close. Something touches the flock from another thread while it closes. The results are not affected.

Decided while building `spec-0094-api`:
- **Split in two.** `spec-0094-api` is the JSON and the account's statement; `spec-0094-api-server` is the server and its routes. The entry's done-when is proved, and the entry ticked, on the second.
- **JSON by hand.** One writer takes maps, lists, strings, numbers and booleans. One reader takes a flat object and answers null for anything else. Values come back as text, and the route parses the numbers.
- **The statement.** The balance ask answers whether the account is open, its balance, and its 50 latest movements, newest first. A movement is a transfer's id and a signed amount. A refusal moves nothing, so it is not a movement.

Decided while building `spec-0094-api-server`:
- **Routes.** `POST /api/accounts {id, amount}` opens an account. `GET /api/accounts/{id}` is its statement. `POST /api/transfers {from, to, amount}` answers `202 {id}`. `GET /api/transfers/{id}` answers `{id, status}`, where the status is `Requested`, `Debited`, `Done` or `Refused`. Bad input is a 400 with `{error}`, an unknown account or transfer is a 404, and an ask that timed out is a 503.
- **Ids.** An id is 1 to 64 letters, digits, `-` or `_`, since the events are written with `|` between fields. A transfer's id is `t-` and a UUID. A transfer to an account that is not open is a 404 before anything is sent.
- **Waiting in the test.** The test does not poll a status. Each node reports every transfer that ends, and the test waits on a blocking queue for the id it sent, then reads the status once.
- **What the test catches.** An API that answers 202 without sending the transfer fails it: the transfer never ends.

Decided while building `spec-0094-consumer`:
- **Served from resources.** `/` is `web/index.html`, with `consumer.js` and `bank.css` beside it. The server serves only names like `name.html`, `.js` or `.css` from `web/`, so a path cannot climb out.
- **Following a transfer.** The page asks for the transfer's status every 200 ms until it is `Done` or `Refused`, then reads the account again.
- **Playwright 1.56.0.** It drives Chromium 141, build 1194, which is the build in `/opt/pw-browsers`, so no `executablePath` is needed. The test task sets `PLAYWRIGHT_BROWSERS_PATH`, from the environment or `/opt/pw-browsers`, and `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. The test ran in headless Chromium 141.0.7390.37.
- **Waiting in the test.** Playwright's `assertThat(…).hasText` waits for the page, so the test has no waiting loop of its own.
- **What the test catches.** A page that does not follow its transfer fails it: the status never reads `Done`.
- **Past the soft cap.** About 360 lines, most of them the page's HTML, CSS and script.
