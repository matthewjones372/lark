#!/usr/bin/env bash
# The benchmarks on <ref> and on this checkout, in rounds on this machine, and red when a row got slower.
# A committed number was measured somewhere else; only a comparison made here says anything about here.
#
#   bench/lark-stream-benchmarks/gate.sh origin/main
#   bench/lark-stream-benchmarks/gate.sh HEAD "ChainBenchmark"      # this working tree against its own last commit
#   GATE_ROUNDS=5 bench/lark-stream-benchmarks/gate.sh origin/main  # more rounds, for a noisy runner
#
# Each round is one fork of every benchmark on each side, in the order A B, B A, A B, ..., so that a machine
# drifting over the job slows both sides alike rather than whichever ran second. Both sides run from a worktree
# of their own: measured from this checkout, identical code read up to 1.7x slower on the concurrent
# benchmarks than from a worktree beside it. This checkout's uncommitted changes go into its worktree; files git
# does not track yet do not.
set -euo pipefail

ref="${1:-origin/main}"
benchmarks="${2:-}"
rounds="${GATE_ROUNDS:-3}"
root="$(git rev-parse --show-toplevel)"
base="$(mktemp -d)"
cand="$(mktemp -d)"
trap 'git -C "$root" worktree remove --force "$base"; git -C "$root" worktree remove --force "$cand"' EXIT
git -C "$root" worktree add --quiet --detach "$base" "$ref"
working="$(git -C "$root" stash create)"
git -C "$root" worktree add --quiet --detach "$cand" "${working:-HEAD}"

# Nothing measured on the base is nothing to have got slower than: the first pull request to add the
# benchmarks passes, and every one after it is compared.
# A base from before spec 0139 keeps the module at the root rather than under bench/.
baseDir="$base/bench/lark-stream-benchmarks"
[ -d "$baseDir" ] || baseDir="$base/lark-stream-benchmarks"
if [ ! -d "$baseDir" ]; then
  echo "$ref has no lark-stream-benchmarks, so there is nothing to compare against"
  exit 0
fi

candDir="$cand/bench/lark-stream-benchmarks"
out="$root/bench/lark-stream-benchmarks/build/rounds"
rm -rf "$out"
mkdir -p "$out"

# One fork of every benchmark on one side, kept as <side>-<round>.json.
measure() {
  local checkout="$1" dir="$2" name="$3"
  (cd "$checkout" && ./gradlew --quiet :lark-stream-benchmarks:jmh -PbenchmarkArgs="-f 1 $benchmarks")
  cp "$dir/build/jmh-result.json" "$out/$name.json"
}

baselines=()
candidates=()
for round in $(seq 1 "$rounds"); do
  if [ $((round % 2)) -eq 1 ]; then
    measure "$base" "$baseDir" "base-$round"
    measure "$cand" "$candDir" "candidate-$round"
  else
    measure "$cand" "$candDir" "candidate-$round"
    measure "$base" "$baseDir" "base-$round"
  fi
  baselines+=("$out/base-$round.json")
  candidates+=("$out/candidate-$round.json")
done

(cd "$root" && ./gradlew --quiet :lark-stream-benchmarks:jmhCompare \
  -Pbaseline="$(IFS=,; echo "${baselines[*]}")" \
  -Pcandidate="$(IFS=,; echo "${candidates[*]}")")
