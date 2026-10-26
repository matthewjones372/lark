# 0164 — A gate that fails on regressions, not on the runner

## Problem

The `benchmarks` gate fails pull requests on rows they never touched. #445, #452,
#453 and #459 failed on `ChainBenchmark.*` and `HandOffBenchmark.*`, with swings of
12–78%, and #459 changed no runtime code at all. A re-run usually passes or fails on
other rows, so people re-run until it is green.

The 43 tables the gate has uploaded (39 runs, re-runs included) say why:

- **The whole candidate half moves together.** In #453's first attempt all four
  `ChainBenchmark` rows read +22% to +29%. In its second attempt, on the same commit,
  they read −7% to −23%. Since Oct 19 the median change across a table's rows has
  reached ±11–12%. Before that it stayed under ±1.2%. `gate.sh` measures all of the
  base and then all of the candidate, so drift over the job's ~25 minutes looks like a
  regression.
- **JMH's error bar doesn't cover that drift.** The ± comes from the iterations inside
  one block, so "outside both error bars" can't tell a code change from a slower
  quarter of an hour.
- **A few rows are noisy within a block too.** `MapPar.actors`, `ManyRuns.actors` and
  `GroupedWithin.*` differ fork to fork. `HandOff.*Actors` is bimodal within a fork.
  `ChainBenchmark`'s fork means agree to about 1.5%, so its swings are drift alone.

## Not doing

- **A threshold against committed numbers.** The README already rules that out.
- **Fixing the benchmarks' own variance**, such as `HandOff.*Actors` being bimodal.
  That belongs to a spec about those benchmarks.
- **A wider tolerance for every row.** At 10% the gate already misses nothing that
  matters. The noise is the problem, not the threshold.
- **Subtracting the table's median shift.** It cancels drift, but it would also hide
  a change that slows every row, which is exactly a regression in `surefold-stream`'s core.

## Shape

`gate.sh` builds both sides once, then runs them in rounds, one fork each, in the
order A B B A A B …, so drift hits both sides equally:

```bash
bench/surefold-stream-benchmarks/gate.sh origin/main               # 3 rounds, every row
GATE_ROUNDS=5 bench/surefold-stream-benchmarks/gate.sh HEAD Chain   # more rounds, one class
```

`jmhCompare` takes each side's round files and pairs them by round. A row is SLOWER
when the median of its per-round ratios (candidate over base, inverted for `thrpt`)
is worse than `tolerance`, and also at least `rounds − 1` of those rounds are worse
than `tolerance / 2`. The error bars stop deciding anything. They are still printed.

```
ChainBenchmark.forks      15.2 -> 16.1  +5.9%  rounds +4.1 +7.0 +5.9  ok
HandOffBenchmark.mergeActors 274 -> 488 +78%   rounds +78 −3 +12      ok (1 of 3)
```

A row flagged that way is measured again, only that row, for `GATE_ROUNDS` more
rounds. It fails the gate only if it is still SLOWER over all the rounds together.
The table shows both verdicts.

## Why this shape

Interleaving goes after the cause the tables show, which is drift between two blocks,
and pairing by round turns that drift into a factor that cancels. Rounds also give
the gate a spread it measured itself, which a single JMH ± can't give. A confirming
re-run only of what failed costs minutes, not another 25. A per-benchmark tolerance
(option 2) would hide drift instead of removing it, and only on the rows already
listed. I recommend holding it in reserve for a row that still fails an A/A run
after this change (stack entry 3).

## Stack

- [x] **`spec-0164-rounds`** ([#462](https://github.com/matthewjones372/lark/pull/462)): `gate.sh` runs A/B rounds, one fork a side per round,
      and `jmhCompare` judges paired rounds. `benchmarks.yml` gains a dispatch input
      for the base ref.
      Done when: 5 dispatches in a row with base = the branch itself pass, and a
      throwaway branch that makes `ChainBenchmark.forks` 30% slower fails on that row
      alone.
- [x] **`spec-0164-confirm`** ([#462](https://github.com/matthewjones372/lark/pull/462)): a flagged row is re-measured alone before it fails.
      Done when: the same 5 + 1 runs hold, and the table shows a row flagged and then
      cleared.
- [x] **`spec-0164-tolerances`** ([#462](https://github.com/matthewjones372/lark/pull/462), [#461](https://github.com/matthewjones372/lark/pull/461)) *(only if needed)*: `gate-tolerance.properties`
      beside the benchmarks, row prefix to tolerance, for a row that still fails A/A.
      Done when: a row listed there is judged by its own tolerance in the table.

## Acceptance

```bash
# in Actions: benchmarks → Run workflow, base = <this branch>, five times: all green
# a throwaway branch with Blackhole.consumeCPU in ChainBenchmark.forks (+30%):
bench/surefold-stream-benchmarks/gate.sh origin/main ChainBenchmark   # red, on forks only
```

## Open questions

0. **Answered: when does the gate run?** Not on every pull request: only on one
   labelled `benchmarks` (and each push to it), or from Run workflow. Landed ahead
   of the stack, on its own.

1. **Answered: how many rounds?** 3, one fork a side each, about 1.5× today's
   measuring. Warmup stays at 5 iterations.
2. **Answered: order.** A B, then B A, alternating round by round.
3. **Answered: agreement.** The median round more than `tolerance` slower and all
   rounds but one more than half of it: 2 of 3 by default.
4. **Answered: the confirming re-run** is second, so rounds alone are measured first.
