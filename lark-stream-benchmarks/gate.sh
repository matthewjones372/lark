#!/usr/bin/env bash
# The benchmarks on <ref> and on this checkout, back to back on this machine, and red when a row got slower.
# A committed number was measured somewhere else; only a comparison made here says anything about here.
#
#   lark-stream-benchmarks/gate.sh origin/main
#   lark-stream-benchmarks/gate.sh HEAD "ChainBenchmark"      # this working tree against its own last commit
set -euo pipefail

ref="${1:-origin/main}"
benchmarks="${2:-}"
root="$(git rev-parse --show-toplevel)"
base="$(mktemp -d)"
git -C "$root" worktree add --quiet --detach "$base" "$ref"
trap 'git -C "$root" worktree remove --force "$base"' EXIT

# Nothing measured on the base is nothing to have got slower than: the first pull request to add the
# benchmarks passes, and every one after it is compared.
if [ ! -d "$base/lark-stream-benchmarks" ]; then
  echo "$ref has no lark-stream-benchmarks, so there is nothing to compare against"
  exit 0
fi

(cd "$base" && ./gradlew --quiet :lark-stream-benchmarks:jmh -PbenchmarkArgs="$benchmarks")
(cd "$root" && ./gradlew --quiet :lark-stream-benchmarks:jmh -PbenchmarkArgs="$benchmarks")
(cd "$root" && ./gradlew --quiet :lark-stream-benchmarks:jmhCompare \
  -Pbaseline="$base/lark-stream-benchmarks/build/jmh-result.json" \
  -Pcandidate="$root/lark-stream-benchmarks/build/jmh-result.json")
