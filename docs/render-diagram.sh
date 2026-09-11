#!/usr/bin/env bash
# Renders the cookbook's wiring fence to docs/wiring.png, and records what it was rendered from.
#
# The fence is golden-tested against `render()`, and DiagramImageTest holds this recording to the
# fence — so a graph that changes fails the build until the picture is made again. Not part of
# `./gradlew build`: it needs a browser, and CI has no business drawing pictures.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
page="$here/cookbook.md"
png="$here/wiring.png"
recorded="$here/wiring.png.sha256"

command -v mmdc >/dev/null || {
    echo "mmdc is not on the PATH: npm install -g @mermaid-js/mermaid-cli" >&2
    exit 1
}

source="$(mktemp)"
trap 'rm -f "$source"' EXIT

python3 - "$page" "$source" <<'PY'
import pathlib, re, sys
page, out = sys.argv[1], sys.argv[2]
fence = re.search(r"<!-- cookbook-diagram -->\s*```mermaid\n(.*?)\n```", pathlib.Path(page).read_text(), re.S)
if fence is None:
    raise SystemExit("cookbook.md has no fence marked <!-- cookbook-diagram -->")
pathlib.Path(out).write_text(fence.group(1) + "\n")
PY

# PUPPETEER_CONFIG points mermaid at a browser it would not otherwise find, and is how a container
# passes --no-sandbox. A developer with a normal Chrome needs none of it.
mmdc --input "$source" --output "$png" --backgroundColor white --scale 3 \
    ${PUPPETEER_CONFIG:+--puppeteerConfigFile "$PUPPETEER_CONFIG"}
# Newline-free, so the test can compare it to a hash it computed the same way.
printf '%s' "$(sha256sum < "$source" | cut -d' ' -f1)" > "$recorded"

echo "wrote $png from the cookbook fence"
