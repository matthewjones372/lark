# 0065 — Closing the gaps

## Problem

0064 left three things undone, and reading the code for them turned up a
fourth that matters more than any of them:

- **Stopped actors are never let go.** A cell is added to its flock's list of
  actors and to its parent's children when it is spawned, and neither list
  drops it when it stops; only the flock's close or the parent's own stop
  empties them. An entities manager spawns a new child every time an entity
  comes back from passivation, so a long-running flock of entities holds every
  incarnation it ever made, with its state, until it closes. The test actors
  keep stopped children in `children` the same way.
- **A tell to a cold actor is slower than Pekko's** in the probe that times
  the tells apart from the actors (about 180 ns against 130 ns). On the 4-core
  machine this was drafted on, the probe does not reproduce it: three runs
  gave lark's tells 1.6–2.8 ms per 10,000 and Pekko's 1.7–3.5 ms, depending on
  which system ran first. The probe is too noisy to decide anything.
- **No test on threads shows a router passing over a full routee**; only the
  test actors' version is covered.

## Not doing

- **Transport, membership, sharding.** Still their own specs, renumbered
  0066–0068.
- **A scheduler of lark's own**, or an intrusive ready queue. 0064 tried a
  swap-only ready queue, and it was slower.
- **Changing what a stop does.** Only who still holds the cell afterwards.

## Shape

- **Let go.** When an actor ends, it leaves its parent's children and its
  flock's list, so nothing of the runtime holds it. The lists become
  concurrent sets, since the child removes itself from its own thread.
  `ctx.stop(child)` of a child that has already stopped is then a no-op, not a
  `require` failure, because the child may have gone on its own. The test
  actors do the same with `children`.
- **The tell's half, measured.** `FanOutBenchmark` gains a row that times only
  the loop of tells, for both systems, under JMH's forks and error bars rather
  than the probe's.
- **A tell touches the cell, and not the last node.** An idle actor's mailbox
  rests on a node that the cell itself is: when an activation empties the
  mailbox, it compare-and-sets the tail back onto the cell. The next tell's
  swap then writes the `next` of an object it has already loaded, instead of
  the last message's node, which has gone cold since. That is one cache miss
  fewer per tell to an idle actor, for one compare-and-set per activation.
- **A router's full routee, on threads.** A pool whose first routee is parked
  on a full mailbox hands the next message to the second.

## Why this shape

The leak comes first because it is a correctness bug in the entities 0063 just
shipped, not a speed row. For the tell: of the objects a tell to an idle actor
touches (the cell, the mailbox's last node, the new node, and the ready
queue), the last node is the one whose miss nothing else pays for, and the
cell can stand in for it. The ready queue was the other candidate, but 0064
measured its alternatives slower. The row comes before the change, so that
the change is judged on JMH's error bars and not on the probe.

## Stack

- [ ] **`spec-0065-let-go`** — ended actors leave their parent's and flock's
      lists; the same for test actors. Done when: after 1,000 passivations of
      one entity the manager has one child, a flock that spawned and stopped
      1,000 actors holds none, and `ctx.stop` of an ended child is a no-op.
- [ ] **`spec-0065-router-full`** — the threads test for a pool passing over
      a full routee. Done when: the test fails with the pass-over removed.
- [ ] **`spec-0065-tell-row`** — the tells-only rows in `FanOutBenchmark`,
      and a README line with where lark stands. Done when: both rows run
      under `:lark-actor-benchmarks:jmh`.
- [ ] **`spec-0065-cell-at-rest`** — the mailbox rests on the cell. Done
      when: the tells-only row is no slower than Pekko's within its error,
      and fan-out, ping-pong, tell and footprint are within their error of
      the 0064 baseline. If the row does not move, the change is dropped and
      the measurement is written down instead.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-actor-benchmarks:jmh -Pjmh.includes=FanOut
```

## Open questions

- **Renumber transport, membership and sharding again, to 0066–0068?**
  Recommended: yes, as 0064 did, so that specs stay in build order.
- **An ended child that `ctx.stop` names: a no-op, or still an error?**
  Recommended: a no-op. The child can end on its own between the parent's
  decision and the call, so an error there is a race the parent cannot avoid.
- **Should the test actors' `children` lose stopped children too?**
  Recommended: yes, so that a test of passivation sees what threads do.
- **If the cell-at-rest change does not beat Pekko's tell, stop there?**
  Recommended: yes. Fan-out already beats Pekko by 23%, and the next
  candidate (the ready queue) was measured slower in 0064.
