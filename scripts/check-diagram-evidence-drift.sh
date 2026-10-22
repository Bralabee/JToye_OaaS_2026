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
#   (a squash merge orphans a branch-local pin), a diagram citing nothing, a failing git or jq,
#   or missing jq prints a `::warning` saying DRIFT NOT COMPUTED and why.
#
#   The comparison is pin..HEAD, so drift a PR did not cause is still announced on that PR:
#   the notice repeats until someone re-pins. And on a pull_request run HEAD is the merge
#   commit, which contains the PR's own commits, so a pin to a branch-local commit reads as
#   an ancestor there and is reported "not an ancestor" only on the push-to-main run after
#   the squash merge. Pin to a commit already on main.
#
# RE-PINNING (docs/architecture/DIAGRAMS.md): set the revision to a commit that is an ancestor
# of origin/main, re-run `archify validate --repo-root`, regenerate with `archify deliver`, and
# have someone who knows the subsystem read the arrows.
#
#   scripts/check-diagram-evidence-drift.sh            check every committed diagram
#   scripts/check-diagram-evidence-drift.sh FILE...    check these diagram JSON files
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

warn() { printf '::warning title=Diagram evidence drift NOT COMPUTED::%s\n' "$*"; }

command -v jq >/dev/null 2>&1 || { warn "jq is not installed"; exit 0; }
# FILE arguments are resolved against the caller's directory BEFORE moving to the repo root.
files=()
for a in "$@"; do case "$a" in /*) files+=("$a") ;; *) files+=("$PWD/$a") ;; esac; done
cd "$ROOT" || { warn "cannot enter $ROOT"; exit 0; }
if [ ${#files[@]} -eq 0 ]; then
  listed=$(git ls-files 'docs/architecture/*.architecture.json') || { warn "git ls-files failed"; exit 0; }
  while IFS= read -r f; do [ -n "$f" ] && files+=("$f"); done <<< "$listed"
fi
[ ${#files[@]} -gt 0 ] || { warn "no docs/architecture/*.architecture.json found"; exit 0; }

# Every value is captured with its own exit status (out=$(cmd); rc=$?): a failing git or jq
# must surface as NOT COMPUTED, never as an empty list that reads "unchanged".
for f in "${files[@]}"; do
  rev=$(jq -r '.meta.repository.revision // empty' "$f" 2>/dev/null) || { warn "$f: not readable JSON"; continue; }
  [ -n "$rev" ] || { warn "$f: no meta.repository.revision"; continue; }
  git cat-file -e "$rev^{commit}" 2>/dev/null || { warn "$f: pinned revision $rev is not in this clone"; continue; }
  git merge-base --is-ancestor "$rev" HEAD 2>/dev/null \
    || { warn "$f: pinned revision ${rev:0:12} is not an ancestor of HEAD; re-pin to a commit on main"; continue; }
  list=$(jq -r '[.. | objects | select(has("path") and has("line")) | .path] | unique[]' "$f" 2>/dev/null) \
    || { warn "$f: could not read its cited paths"; continue; }
  cited=(); while IFS= read -r p; do [ -n "$p" ] && cited+=("$p"); done <<< "$list"
  [ ${#cited[@]} -gt 0 ] || { warn "$f: cites no files, so there is nothing to compare"; continue; }
  diffout=$(git diff --name-only "$rev" HEAD -- "${cited[@]}") || { warn "$f: git diff ${rev:0:12}..HEAD failed"; continue; }
  changed=(); while IFS= read -r p; do [ -n "$p" ] && changed+=("$p"); done <<< "$diffout"
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
