#!/usr/bin/env bash
# go-test-json-to-tap.sh — render a `go test -json` stream as TAP version 13.
#
# WHY THIS EXISTS (plan 37-25, the first Go TDD plan in Phase 37)
#   The RED-evidence classifier (gsd-core bin/lib/report-parser.cjs) reads TAP,
#   JUnit XML, swift-testing and Python unittest. `go test` emits none of them,
#   and no Go-to-JUnit tool is installed on this machine (installing one is
#   outside the plan's threat model T-37-SC). This script is a FORMAT RENDERER
#   over the real run's own machine-readable output: every test point below is
#   one `"Action":"pass"|"fail"|"skip"` event with a `Test` field from the
#   stream, nothing is added or dropped, and the raw stream is kept next to the
#   record so the rendering can be checked against it.
#
# USAGE
#   go test -count=1 -json -run 'X' ./pkg/ > raw.jsonl ; rc=$?
#   bash go-test-json-to-tap.sh raw.jsonl > report.tap
#
# Subtests are emitted as their own flat points ("Parent/Sub"), never as
# buffered TAP subtests, so a strict parser needs no closing brace.
# Output lines of a FAILED test are attached as a YAML diagnostic block.
set -euo pipefail
in="${1:?usage: $0 go-test-json.jsonl}"
[ -s "$in" ] || { echo "VOID: $in is missing or empty" >&2; exit 2; }
command -v jq >/dev/null || { echo "VOID: jq not found" >&2; exit 2; }

echo "TAP version 13"
# One point per terminal test event, in stream order.
mapfile -t points < <(jq -r 'select(.Test != null and (.Action=="pass" or .Action=="fail" or .Action=="skip")) | "\(.Action)\t\(.Package)\t\(.Test)"' "$in")
n=0
for p in "${points[@]}"; do
  IFS=$'\t' read -r action pkg name <<<"$p"
  n=$((n+1))
  case "$action" in
    pass) echo "ok $n - $name" ;;
    skip) echo "ok $n - $name # SKIP" ;;
    fail)
      echo "not ok $n - $name"
      echo "  ---"
      echo "  package: $pkg"
      echo "  output: |"
      jq -r --arg t "$name" 'select(.Test == $t and .Action=="output") | .Output' "$in" \
        | sed -e 's/[[:space:]]*$//' -e 's/^/    /'
      echo "  ..."
      ;;
  esac
done
echo "1..$n"
