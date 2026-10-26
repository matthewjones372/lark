# 0187 — An adaptive limit that keeps up

## Problem

Spec 0171 defined "Surefold wins" in `blocking-vs-reactive`'s `overload` scenario as equal throughput and a lower p99
after the dependency loses capacity. `Limit.adaptive` meets half of that. Its p99 after the drop is 10 ms, against
Reactor's best fixed concurrency at 30 ms, but it finishes 13% later (7.23 s against 6.26 s). The README puts that
down to ramping up from 20. A per-window trace (`Vegas` printing each decision) shows the ramp is not the main
cost:

- **Jitter reads as queueing.** The no-queue, short-queue and long-queue thresholds are a fixed number of calls
  (1, `3·log10`, `6·log10`). At a limit of 400 a long queue is 16 calls, a 4% rise in latency, and this machine's
  window averages vary by 2% (median) to 6% (p90) with nothing queueing. So below the dependency's capacity of 500
  the limit kept cutting, held 255–440, and averaged 56% of capacity until the drop.
- **One drop is cut many times.** The windows just after a cut still time calls that queued behind the higher
  limit before it. Reading them against the lower limit cuts again, and the limit fell from 217 to 29 against a
  capacity of 100.
- The ramp from 20 cost about 0.1 s of the 1 s.

## Not doing

- No API change. `Limit.adaptive(name, initial, min, max)` keeps its parameters, and `Vegas` stays internal.
- No change to `Limit.fixed`, to how a failure or a cancellation counts, or to the metrics.
- No other algorithm (Gradient2, AIMD). This keeps Vegas and fixes how it reads its samples.
- No change in `blocking-vs-reactive`.

## Shape

Inside `Vegas.kt`, and `Limit.withPermit` passing what each call found inside when it started:

- **Thresholds scale with the limit as well as `log10`**: no queue below 2% of the limit, short below 5%, long from
  15%.
- **A cut goes to what the dependency is serving**, by Little's law on the calls inside: the larger of the limit
  and what each call of the window found when it started. After a cut the second is the higher limit before it,
  so the next window does not cut again on calls that queued under the old limit.
- **Slow start.** The limit doubles each window until the first window with any queue, as TCP does.
- **A probe once an epoch** (100 windows). The limit drops to what the dependency is serving for a window, as
  BBR probes for its round trip, so every epoch has a window with no queue. The fastest window of the epoch is then
  safe to forget into unloaded. Without the probe, a limit that holds 10% over capacity never sees an unqueued
  window, and forgetting ratchets it upward each epoch.

## Why this shape

Each piece answers one thing the trace showed, measured alone before the next was added. Thresholds as a fraction
of the limit are what jitter needs, since a few percent of jitter reads as a few percent of the limit queueing.
Little's law on what calls found when they started is the honest capacity estimate in the windows after a cut,
which is when the limit-based one is wrong. A cut-the-next-window-only rule (skipping calls that started before a
cut) also stops the cascade, but waits a whole drain before the second cut and raised p99 after the drop to 15–20
ms. A rate-based estimate (calls per unit time) needs a clock and reads wrong when completions bunch up.

## Stack

- [x] **`limit-ramp-up`** — the four changes above, `LimitTest` cases on a simulated dependency (steady, losing
      capacity, gaining capacity, noisy, slower for good, and held over capacity for 40 s), and the `overload` row
      before and after each change.
      Done when: `overload` finishes within about 3% of the best fixed concurrency with p99 after the drop about
      10 ms or better, or the PR says what is left and why (it does: 4.4%, see Open questions).

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :surefold:build :surefold-stream-forks:build :surefold-stream-parity:build
# in blocking-vs-reactive, against this branch published to mavenLocal
build/install/blocking-vs-reactive/bin/blocking-vs-reactive overload surefold-adaptive
```

## Open questions

- **Is within 3% of the best fixed concurrency reachable?** Not quite. The adaptive limit now finishes in 6.44 s
  (median of four) against 6.17 s for `reactor-tuned` and `surefold-1000`, 4.4% behind, with p99 after the drop at
  10.2 ms. Surefold with its limit pinned at 500, 550 or 600 takes 6.41–6.47 s, so the limit now matches Surefold at the
  right fixed number. The rest is the time a freed permit takes to be taken up, which only a queue of hundreds
  inside the dependency hides (`surefold-1000`'s p99 before the drop is 12 ms against 9.5). Deferred to a spec on
  `Limit`'s hand-off.
- **How much queue should the limit hold?** Answered by measuring: up to 15% of the limit before it cuts. Raising
  the bands to 10% and 25% left throughput unchanged and raised p99 after the drop to 11–15 ms.
- **Smaller windows, or growth in proportion to headroom?** Answered: no. Half-limit windows were 6% slower;
  double-limit windows raised p99 after the drop to 13 and 26 ms in two runs of three; headroom growth changed
  nothing measurable.
