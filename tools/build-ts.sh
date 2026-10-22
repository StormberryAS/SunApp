#!/usr/bin/env bash
# Compile SunApp's TypeScript (ts/) into the JavaScript the page loads.
#
# Copied on 21 October 2026 from EclipseTracker's tools/build-ts.sh (itself from the Website's, the
# TypeScript move of 2026-10-14) and cut down to what SunApp has: one page script, ts/app.ts, which
# compiles to app.js at the repository root. SunApp has no ES modules (index.html loads suncalc.js,
# cities.js and app.js as classic scripts sharing one global scope), no DOM-free core of its own
# (its maths is the vendored suncalc.js), no generated data step and no script injected into the
# Android app (the app is native Kotlin with no WebView), so EclipseTracker's tsconfig.core.json,
# build-eclipse-data.py and bridge steps have nothing to do here and are left out.
#
# The service worker of the offline pilot (21 October 2026), ts/sw.ts, compiles to sw.js the same
# way, as a second program: tsconfig.sw.json checks it against the WebWorker library instead of the
# DOM (it runs in a worker, where there is no document), and tsconfig.json leaves it out. Both
# programs write into one scratch folder, so everything below treats app.js and sw.js alike.
#
#   tools/build-ts.sh           install the pinned compiler, compile every ts/**/*.ts (the .d.ts
#                               declarations aside) into the .js of the same path at the repository
#                               root (ts/app.ts -> app.js, ts/sw.ts -> sw.js), then run
#                               bump_assets.py so every page, and the file list in sw.js, carries
#                               the current ?v= stamp
#   tools/build-ts.sh --force   the same, but overwrite a compiled .js even when it holds a hand edit
#   tools/build-ts.sh --check   change nothing: fail when a compiled .js is missing, is not tracked
#                               by git, or is not (stamps aside) byte for byte what ts/ compiles to;
#                               when the root holds a .js that no ts/ source compiles to, other than
#                               the generated cities.js and the vendored suncalc.js (a stray left
#                               behind by a renamed or deleted source, which the site would still
#                               publish); when a page carries a stale ?v=; or when cities.js and
#                               data/cities.tsv disagree (tools/check-city-parity.py)
#
# Every mode also type-checks the tests and the Node tools (tests/*.ts and tools/*.ts, run by
# Node 24 directly) with tests/tsconfig.json, strict; it writes nothing, and one that does not
# type-check fails the build and the check.
#
# The compiled .js files are committed on purpose: GitHub Pages publishes the repository's files as
# they are, with no build step of its own. Edit ts/*.ts, never the .js.
#
# Stamps. app.js imports nothing and carries no ?v= stamp, but sw.js does: bump_assets.py stamps the
# './x.js' and './x.css' literals of its file list. normalise() strips them, as in EclipseTracker,
# so a stamped relative URL inside compiled output (a ./x.js?v=0123abcd or ./x.css?v=0123abcd
# literal) never counts as a difference; bump_assets.py --check proves the stamps themselves are
# current.
#
# Hand edits. A build never overwrites a compiled .js silently. It writes only when the .js now at
# the root, normalised, is one some ts/ source produced: what the committed ts/ (HEAD) compiles to,
# what the current ts/ compiles to, or what this script wrote last time (its record lives in the git
# directory, so it is never committed or published). Anything else is an edit made in the .js
# itself: the build stops and writes nothing. Port the edit into ts/ and build again, or rerun with
# --force to drop it. (The very first build after the move, when app.js was still the hand-written
# 1.1.3 file and HEAD had no ts/, needed --force for exactly this reason.)
#
# The compiler is the exact version in package-lock.json, installed with `npm ci` (a frozen install)
# and with install scripts off. Needs Node and npm (Node 24 on the Dell), python3, and git for the
# hand-edit guard and the tracked-file check (both are skipped outside a git checkout).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

case "$#:${1:-}" in
  0:)        MODE=build ;;
  1:--force) MODE=force ;;
  1:--check) MODE=check ;;
  *)         echo "usage: $0 [--check | --force]" >&2; exit 2 ;;
esac

die() { echo "build-ts: $*" >&2; exit 1; }
command -v node >/dev/null || die "node not found"
command -v npm >/dev/null || die "npm not found"
command -v python3 >/dev/null || die "python3 not found"
GIT=0
if command -v git >/dev/null && [ "$(git rev-parse --show-toplevel 2>/dev/null)" = "$ROOT" ]; then GIT=1; fi

npm ci --ignore-scripts --no-audit --no-fund --prefer-offline --loglevel=error >/dev/null \
  || die "npm ci failed (package.json and package-lock.json must agree; nothing was compiled)"
TSC=(node "$ROOT/node_modules/typescript/bin/tsc")
echo "build-ts: TypeScript $("${TSC[@]}" --version | sed 's/^Version //')"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# A relative URL with its ?v= stamp removed: from './x.js?v=0123abcd' to './x.js' (and .css alike).
normalise() { sed -E "s#(['\"]\\.{1,2}/[^'\"?]+\\.(js|css))\\?v=[0-9a-f]{8}(['\"])#\\1\\3#g" "$1"; }
nsha() { normalise "$1" | sha256sum | cut -d' ' -f1; }

"${TSC[@]}" -p "$ROOT/tests/tsconfig.json" || die "the tests or the Node tools (tests/*.ts, tools/*.ts) do not type-check; nothing was written"
"${TSC[@]}" -p "$ROOT/tsconfig.json" --outDir "$tmp/out" || die "ts/ does not compile; nothing was written"
"${TSC[@]}" -p "$ROOT/tsconfig.sw.json" --outDir "$tmp/out" || die "ts/sw.ts does not compile; nothing was written"

mapfile -t OUTPUTS < <(cd "$tmp/out" && find . -type f -printf '%P\n' | LC_ALL=C sort)
mapfile -t EXPECTED < <(cd ts && find . -type f -name '*.ts' ! -name '*.d.ts' -printf '%P\n' | sed 's/\.ts$/.js/' | LC_ALL=C sort)
[ "${#OUTPUTS[@]}" -gt 0 ] || die "tsc emitted nothing"
[ "${OUTPUTS[*]}" = "${EXPECTED[*]}" ] \
  || die "tsc emitted [${OUTPUTS[*]}] but ts/ holds sources for [${EXPECTED[*]}]; look at tsconfig.json"

# The JavaScript at the root that is not compiled from ts/: generated by update_cities.py, and the
# vendored SunCalc 1.8.0. Nothing else may sit beside the compiled files.
NOT_COMPILED=(cities.js suncalc.js)

tracked() { [ "$GIT" = 1 ] && git ls-files --error-unmatch -- "$1" >/dev/null 2>&1; }

if [ "$MODE" = check ]; then
  bad=0
  [ "$GIT" = 1 ] || echo "build-ts: not a git checkout; skipping the tracked-file check"
  for f in "${OUTPUTS[@]}"; do
    if [ ! -f "$f" ]; then
      echo "build-ts: $f is missing: run tools/build-ts.sh" >&2; bad=1
    elif [ "$(nsha "$f")" != "$(nsha "$tmp/out/$f")" ]; then
      echo "build-ts: $f is NOT what ts/ compiles to: edit ts/, then run tools/build-ts.sh" >&2; bad=1
    elif [ "$GIT" = 1 ] && ! tracked "$f"; then
      echo "build-ts: $f is what ts/ compiles to but is not tracked by git: git add it" >&2; bad=1
    else
      echo "build-ts: $f matches ts/"
    fi
  done
  # JavaScript at the root with no source: only the compiled files, cities.js and suncalc.js.
  mapfile -t PRESENT < <(find . -maxdepth 1 -type f \( -name '*.js' -o -name '*.mjs' -o -name '*.cjs' \) -printf '%P\n' | LC_ALL=C sort)
  for f in "${PRESENT[@]}"; do
    case " ${OUTPUTS[*]} ${NOT_COMPILED[*]} " in
      *" $f "*) ;;
      *) echo "build-ts: $f is JavaScript that no ts/ source compiles to: delete it (git rm), or restore its source" >&2; bad=1 ;;
    esac
  done
  python3 -I "$ROOT/bump_assets.py" --check || bad=1
  python3 -I "$ROOT/tools/check-city-parity.py" || bad=1
  [ "$bad" = 0 ] || exit 1
  echo "build-ts: check passed"
  exit 0
fi

STATE=""
[ "$GIT" = 1 ] && STATE="$(git rev-parse --absolute-git-dir)/build-ts.last"

if [ "$MODE" = build ]; then
  if [ "$GIT" = 1 ] && git cat-file -e HEAD:tsconfig.json 2>/dev/null && git cat-file -e HEAD:ts 2>/dev/null; then
    mkdir -p "$tmp/head"
    head_sw=()
    git cat-file -e HEAD:tsconfig.sw.json 2>/dev/null && head_sw=(tsconfig.sw.json)
    git archive HEAD tsconfig.json "${head_sw[@]}" ts | tar -x -C "$tmp/head"
    "${TSC[@]}" -p "$tmp/head/tsconfig.json" --outDir "$tmp/head-out" >/dev/null 2>&1 || true
    if [ "${#head_sw[@]}" -gt 0 ]; then
      "${TSC[@]}" -p "$tmp/head/tsconfig.sw.json" --outDir "$tmp/head-out" >/dev/null 2>&1 || true
    fi
  fi
  hand=()
  for f in "${OUTPUTS[@]}"; do
    [ -f "$f" ] || continue
    cur="$(nsha "$f")"
    [ "$cur" = "$(nsha "$tmp/out/$f")" ] && continue
    [ -f "$tmp/head-out/$f" ] && [ "$cur" = "$(nsha "$tmp/head-out/$f")" ] && continue
    [ -n "$STATE" ] && [ -f "$STATE" ] && grep -qxF "$cur  $f" "$STATE" && continue
    hand+=("$f")
  done
  if [ "${#hand[@]}" -gt 0 ]; then
    for f in "${hand[@]}"; do
      echo "build-ts: $f holds changes that no TypeScript source produced (edited by hand?)" >&2
    done
    die "nothing was written. Port the change into its TypeScript source (git diff and git log -p show it), then build again; or run tools/build-ts.sh --force to overwrite it."
  fi
fi

wrote=0
for f in "${OUTPUTS[@]}"; do
  if [ -f "$f" ] && [ "$(nsha "$f")" = "$(nsha "$tmp/out/$f")" ]; then continue; fi
  mkdir -p "$(dirname "$f")"
  cp "$tmp/out/$f" "$f"
  echo "build-ts: wrote $f"
  wrote=1
  if [ "$GIT" = 1 ] && ! tracked "$f"; then
    echo "build-ts: $f is new: git add it" >&2
  fi
done
[ "$wrote" = 1 ] || echo "build-ts: compiled output unchanged"
if [ -n "$STATE" ]; then
  for f in "${OUTPUTS[@]}"; do printf '%s  %s\n' "$(nsha "$f")" "$f"; done > "$STATE"
fi

# Always: bump_assets.py changes nothing when every stamp is current, and repairs a stale one even
# when the compiled output did not change.
python3 -I "$ROOT/bump_assets.py"
