#!/usr/bin/env bash
# check-diagram-evidence-drift.sh — NOTICE (never a block) when a file an archify diagram cites
# has changed since the revision the diagram is pinned to.
#
# WHY A NOTICE AND NOT A GATE
#
#   Each docs/architecture/*.architecture.json pins meta.repository.revision and cites files
#   (components[].sources[].path). `archify validate` proves those files EXIST at the pin; it
#   cannot prove the arrows are still true. When a cited file moves on, a human should re-read
#   the diagram. Measured 2026-09-23: cited files were touched by ~1 merged PR in 14, mostly by
#   unrelated fixes, so a blocking check would fail PRs that did nothing wrong. This prints a
#   GitHub `::notice` per changed file and always exits 0.
#
#   "Could not compute" is never silent: a missing pin, a pin that is not an ancestor of HEAD
#   (a squash merge orphans a branch-local pin), a diagram citing nothing, or missing jq prints
#   a `::warning` saying DRIFT NOT COMPUTED and why.
#
# RE-PINNING (docs/architecture/DIAGRAMS.md): set the revision to a commit that is an ancestor
# of origin/main, re-run `archify validate --repo-root`, regenerate with `archify deliver`, and
# have someone who knows the subsystem read the arrows.
#
#   scripts/check-diagram-evidence-drift.sh            check every committed diagram
#   scripts/check-diagram-evidence-drift.sh FILE...    check these diagram JSON files
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 0

warn() { printf '::warning title=Diagram evidence drift NOT COMPUTED::%s\n' "$*"; }

command -v jq >/dev/null 2>&1 || { warn "jq is not installed"; exit 0; }
if [ $# -gt 0 ]; then files=("$@"); else mapfile -t files < <(git ls-files 'docs/architecture/*.architecture.json'); fi
[ ${#files[@]} -gt 0 ] || { warn "no docs/architecture/*.architecture.json found"; exit 0; }

for f in "${files[@]}"; do
  rev=$(jq -r '.meta.repository.revision // empty' "$f" 2>/dev/null)
  [ -n "$rev" ] || { warn "$f: no meta.repository.revision"; continue; }
  git cat-file -e "$rev^{commit}" 2>/dev/null || { warn "$f: pinned revision $rev is not in this clone"; continue; }
  git merge-base --is-ancestor "$rev" HEAD 2>/dev/null \
    || { warn "$f: pinned revision ${rev:0:12} is not an ancestor of HEAD; re-pin to a commit on main"; continue; }
  mapfile -t cited < <(jq -r '[.. | objects | select(has("path") and has("line")) | .path] | unique[]' "$f" 2>/dev/null)
  [ ${#cited[@]} -gt 0 ] || { warn "$f: cites no files, so there is nothing to compare"; continue; }
  mapfile -t changed < <(git diff --name-only "$rev" HEAD -- "${cited[@]}")
  if [ ${#changed[@]} -eq 0 ]; then
    echo "$f: all ${#cited[@]} cited file(s) unchanged since ${rev:0:12}"
    continue
  fi
  for c in "${changed[@]}"; do
    printf '::notice file=%s,title=Diagram evidence moved::%s changed since the diagram'\''s pin %s; re-read its arrows and re-pin (docs/architecture/DIAGRAMS.md)\n' \
      "$f" "$c" "${rev:0:12}"
  done
  echo "$f: ${#changed[@]} of ${#cited[@]} cited file(s) changed since ${rev:0:12}"
done
exit 0
