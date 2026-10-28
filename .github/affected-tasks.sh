#!/usr/bin/env bash
# The Gradle tasks a pull request needs, from the files it changes against $1 (its base: HEAD^1 on GitHub's merge
# commit, origin/main on a branch):
#
# - SpecsSuite alone, when every change is under specs/, since it is the one test that reads them;
# - each module a changed file is in, built with everything that depends on it (`buildDependents`), so a change to
#   surefold-cache builds and tests surefold-cache, surefold-cache-postgres and so on, and nothing else;
# - each module whose build file names a changed module's task (`":surefold-compiler:publish..."`, as a TestKit test
#   does to resolve it), tested, since `buildDependents` follows only declared dependencies;
# - each module whose build file names a changed file outside the modules, such as a guide it holds to a compiled
#   example, tested;
# - surefold-app's tests always, since its suites check the whole repository: specs, docs, module lists, names.
#
# A change that can reach every module (the root build, settings, the wrapper, this script or the workflow) prints
# `build`, the full build. So does a pull request whose base cannot be found. A push to main always runs the full
# build in the workflow, so a test this narrowing skips still runs before anything is released.
set -euo pipefail

base="${1:?usage: affected-tasks.sh <base ref>}"
cd "$(git rev-parse --show-toplevel)"

if ! changed=$(git diff --name-only "$base"...HEAD 2>/dev/null); then
  echo build
  exit 0
fi

reaches_everything='^(settings\.gradle\.kts|build\.gradle\.kts|gradle\.properties|gradlew|gradlew\.bat|gradle/|buildSrc/|\.github/workflows/build\.yml|\.github/affected-tasks\.sh)'
if grep -Eq "$reaches_everything" <<<"$changed"; then
  echo build
  exit 0
fi

if ! grep -qv '^specs/' <<<"$changed"; then
  echo ":surefold-app:test --tests *SpecsSuite*"
  exit 0
fi

# Every module as "group/name", from settings.gradle.kts's module(group, name) calls.
modules=$(grep -oE '^module\("[^"]+", "[^"]+"\)' settings.gradle.kts | sed -E 's/module\("([^"]+)", "([^"]+)"\)/\1\/\2/')

tasks=(":surefold-app:test")
while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  owner=""
  while IFS= read -r module; do
    if [[ "$file" == "$module/"* ]]; then
      owner="$module"
      break
    fi
  done <<<"$modules"
  if [[ -n "$owner" ]]; then
    name="${owner#*/}"
    tasks+=(":$name:buildDependents")
    # Modules that reach this one through a task, not a dependency.
    while IFS= read -r build; do
      [[ -n "$build" ]] && tasks+=(":$(basename "$(dirname "$build")"):test")
    done < <(git ls-files '*/*/build.gradle.kts' | xargs grep -lF -- "\":$name:" 2>/dev/null || true)
  else
    # Outside every module: the modules whose build file names it read it in a test.
    while IFS= read -r build; do
      [[ -n "$build" ]] && tasks+=(":$(basename "$(dirname "$build")"):test")
    done < <(git ls-files '*/*/build.gradle.kts' | xargs grep -lF -- "\"$file\"" 2>/dev/null || true)
  fi
done <<<"$changed"

printf '%s\n' "${tasks[@]}" | sort -u | tr '\n' ' '
echo
