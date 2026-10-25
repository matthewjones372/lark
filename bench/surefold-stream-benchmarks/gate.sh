#!/usr/bin/env bash
# The benchmarks on <ref> and on this checkout, alternated on this machine, and red when a row got slower.
# A committed number was measured somewhere else; only a comparison made here says anything about here.
#
#   bench/surefold-stream-benchmarks/gate.sh origin/main
#   bench/surefold-stream-benchmarks/gate.sh HEAD "ChainBenchmark"      # this working tree against its own last commit
#   GATE_ROUNDS=5 bench/surefold-stream-benchmarks/gate.sh origin/main  # more rounds, for a noisy runner
#
# Each round runs every benchmark once on each side, one fork each, the two sides of a benchmark back to back and
# which goes first alternating. A runner that slows down for a minute then slows both sides of the benchmarks it
# measured in that minute, where running one side's suite and then the other's put the minute on one side only.
# Both sides run from a worktree of their own. This checkout's uncommitted changes go into its worktree; files git
# does not track yet do not.
set -euo pipefail

ref="${1:-origin/main}"
benchmarks="${2:-}"
rounds="${GATE_ROUNDS:-3}"
root="$(git rev-parse --show-toplevel)"
here="$root/bench/surefold-stream-benchmarks"
base="$(mktemp -d)"
cand="$(mktemp -d)"
trap 'git -C "$root" worktree remove --force "$base"; git -C "$root" worktree remove --force "$cand"' EXIT
git -C "$root" worktree add --quiet --detach "$base" "$ref"
working="$(git -C "$root" stash create)"
git -C "$root" worktree add --quiet --detach "$cand" "${working:-HEAD}"

# Nothing measured on the base is nothing to have got slower than: the first pull request to add the
# benchmarks passes, and every one after it is compared.
# A base from before spec 0139 keeps the module at the root rather than under bench/.
[ -d "$base/bench/surefold-stream-benchmarks" ] || [ -d "$base/surefold-stream-benchmarks" ] || {
  echo "$ref has no surefold-stream-benchmarks, so there is nothing to compare against"
  exit 0
}

out="$here/build/rounds"
rm -rf "$out"
mkdir -p "$out/runs"

# Builds a side, through its own jmh task with JMH told only to list, and keeps the JVM and classpath that task
# would run with in <side>.classpath and the benchmarks the pattern matches in <side>.list.
prepare() {
  local checkout="$1" side="$2"
  (cd "$checkout" && GATE_CLASSPATH="$out/$side.classpath" ./gradlew --quiet --no-configuration-cache \
    --init-script "$here/gate-classpath.gradle" :surefold-stream-benchmarks:jmh -PbenchmarkArgs="-l" > /dev/null)
  "$(sed -n 1p "$out/$side.classpath")" -cp "$(sed -n 2p "$out/$side.classpath")" org.openjdk.jmh.Main \
    -l $benchmarks < /dev/null | grep -E '^[A-Za-z0-9_.$]+$' | grep -F . > "$out/$side.list" || true
}
prepare "$base" base
prepare "$cand" candidate
# The base may lack a benchmark the change adds, but either side listing nothing at all would pass on nothing.
if [ ! -s "$out/candidate.list" ] || { [ -z "$benchmarks" ] && [ ! -s "$out/base.list" ]; }; then
  echo "nothing to measure: no benchmark matches '$benchmarks' on one side" >&2
  exit 1
fi

# One fork of one benchmark on one side, kept as runs/<side>-<round>-<benchmark>.json.
measure() {
  local side="$1" round="$2" benchmark="$3"
  grep -qxF "$benchmark" "$out/$side.list" || return 0
  echo "round $round, $side: $benchmark"
  local java classpath
  java="$(sed -n 1p "$out/$side.classpath")"
  classpath="$(sed -n 2p "$out/$side.classpath")"
  "$java" -cp "$classpath" org.openjdk.jmh.Main -f 1 -prof gc -rf json \
    -rff "$out/runs/$side-$round-$benchmark.json" "^${benchmark//./\\.}\$" < /dev/null > /dev/null
}

# A round's runs on one side as one JMH result, which is what jmhCompare reads.
collect() {
  local side="$1" round="$2" first=1
  {
    echo "["
    for run in "$out/runs/$side-$round-"*.json; do
      [ -e "$run" ] || continue
      [ "$first" -eq 1 ] || echo ","
      first=0
      # JMH writes the array's brackets alone on their lines, with blank lines after: keep what is between.
      grep -v -e '^\[$' -e '^\]$' -e '^$' "$run"
    done
    echo "]"
  } > "$out/$side-$round.json"
}

baselines=()
candidates=()

# One round of the benchmarks listed in <list>, each on both sides back to back, the first side alternating.
measure_round() {
  local round="$1" list="$2" index=0
  while read -r benchmark; do
    if [ $(((index + round) % 2)) -eq 1 ]; then
      measure base "$round" "$benchmark"
      measure candidate "$round" "$benchmark"
    else
      measure candidate "$round" "$benchmark"
      measure base "$round" "$benchmark"
    fi
    index=$((index + 1))
  done < "$list"
  collect base "$round"
  collect candidate "$round"
  baselines+=("$out/base-$round.json")
  candidates+=("$out/candidate-$round.json")
}

compare() {
  rm -f "$here/build/jmh-slower.txt"
  (cd "$root" && ./gradlew --quiet :surefold-stream-benchmarks:jmhCompare \
    -Pbaseline="$(IFS=,; echo "${baselines[*]}")" \
    -Pcandidate="$(IFS=,; echo "${candidates[*]}")")
}

for round in $(seq 1 "$rounds"); do
  measure_round "$round" "$out/candidate.list"
done
compare && exit 0

# A row found slower is measured again, on its own, for as many rounds more, and judged on all of them: one fork
# that ran far off its others no longer fails the gate, and a row that is really slower is slower every round.
slower="$here/build/jmh-slower.txt"
[ -s "$slower" ] || exit 1
cp "$here/build/jmh-compare.txt" "$out/first-compare.txt"
# jmhCompare names a row without its package, as `ChainBenchmark.forks`; the list has it whole.
awk 'NR == FNR { slower["." $0] = 1; next } { for (row in slower) if (substr($0, length($0) - length(row) + 1) == row) print }' \
  "$slower" "$out/candidate.list" > "$out/again.list"
echo "measuring $(wc -l < "$out/again.list") slower row(s) again"
for round in $(seq $((rounds + 1)) $((rounds * 2))); do
  measure_round "$round" "$out/again.list"
done
compare
