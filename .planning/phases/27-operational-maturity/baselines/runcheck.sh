#!/usr/bin/env bash
# runcheck — run one acceptance-criterion arm and report it honestly.
#
# WHY THIS EXISTS. During Task 0 a break table reported rc=0 for every arm, including two that were
# correctly exiting 2. The harness was:
#
#     out=$(cmd); echo "$out"; echo "rc=$?"      # <-- $? is the echo's, not cmd's
#
# The gate was right and the harness was green-washing — the direction that hides defects. This is
# HANDOFF.md §8's standing trap ("a gate piped into tail/head reports the pipe's exit code") arriving
# through a different door, so a note saying "be careful" would not have caught it. A helper does.
#
# USAGE
#     runcheck.sh <expected_rc> <label> -- <command> [args...]
#
#     runcheck.sh 0 "AC-1.1 pass"        -- ./scripts/check-alert-liveness.sh
#     runcheck.sh 2 "AC-1.1 break: VOID" -- env PATH=/usr/bin ./scripts/check-alert-liveness.sh
#
# Exit: 0 when the observed rc equals <expected_rc>, else 1. So a break arm that fails to break is
# itself a failure, which is the whole point — you cannot record "expected 2" and quietly get 0.
#
# Use `any` as <expected_rc> only to record an exploratory arm; it always reports MATCH and is
# labelled UNASSERTED in the output so it cannot be mistaken for a proven arm.
set -u

if [ "$#" -lt 4 ]; then
  echo "usage: runcheck.sh <expected_rc|any> <label> -- <command> [args...]" >&2
  exit 2
fi

expected="$1"; shift
label="$1"; shift
[ "$1" = "--" ] || { echo "runcheck: third argument must be '--', got '$1'" >&2; exit 2; }
shift

# Capture on the SAME line as the call. Nothing may run between the command and reading $?.
out=$("$@" 2>&1); rc=$?

printf '=== %s\n' "$label"
printf -- '--- command ---\n%s\n' "$*"
printf -- '--- output ---\n%s\n' "$out"

if [ "$expected" = "any" ]; then
  printf -- '--- rc=%s (UNASSERTED — exploratory, not evidence) ---\n\n' "$rc"
  exit 0
fi

if [ "$rc" -eq "$expected" ]; then
  printf -- '--- rc=%s  expected=%s  MATCH ---\n\n' "$rc" "$expected"
  exit 0
fi

printf -- '--- rc=%s  expected=%s  *** MISMATCH *** ---\n\n' "$rc" "$expected"
exit 1
