#!/usr/bin/env bash
# AC-0.2 (STRENGTHENED) — see AC-0.2-DEFECT.md for why the plan's own form is unfalsifiable.
#
# Asserts: every baseline's CLOSED_BY: names a criterion the plan actually DEFINES.
#   exit 0 = all resolve
#   exit 1 = at least one DANGLING pointer
#   exit 2 = VOID — unusable input. "Found nothing" is never "clean".
#
# Run from anywhere: paths resolve relative to this script.
set -u

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PHASE_DIR="$(dirname -- "$HERE")"
PLAN="$PHASE_DIR/27-00-PLAN.md"

[ -s "$PLAN" ] || { echo "VOID: plan file missing or empty: $PLAN"; exit 2; }

files=$(ls "$HERE"/B-*.txt 2>/dev/null)
[ -n "$files" ] || { echo "VOID: no baseline files discovered under $HERE"; exit 2; }

# Criteria the plan DEFINES (bolded bullet), not merely mentions in prose. This distinction is what
# makes the plan's own prescribed break sentinel (AC-99.9) fail correctly: it is mentioned once, in
# AC-0.2's own text, and defined nowhere.
defined=$(command grep -oE '\*\*AC-[0-9]+\.[0-9]+' "$PLAN" | sed 's/^\*\*//' | sort -u)
[ -n "$defined" ] || { echo "VOID: extracted zero defined criteria from $PLAN"; exit 2; }

rc=0
n=0
for f in $files; do
  n=$((n + 1))
  ac=$(sed -n 's/^CLOSED_BY: //p' "$f" | head -1)
  if [ -z "$ac" ]; then
    echo "VOID $(basename "$f"): no CLOSED_BY line"
    rc=2
    continue
  fi
  # -x -F: whole-line literal. An unanchored regex would let '.' match any character,
  # so AC-1.1 would also match a hypothetical AC-1X1.
  if ! command grep -qxF -- "$ac" <<<"$defined"; then
    echo "DANGLING $(basename "$f") -> $ac (not a criterion DEFINED in 27-00-PLAN.md)"
    [ "$rc" -eq 0 ] && rc=1
  fi
done

[ "$rc" -eq 0 ] && echo "AC-0.2 OK: $n baselines, every CLOSED_BY resolves to a defined criterion"
exit "$rc"
