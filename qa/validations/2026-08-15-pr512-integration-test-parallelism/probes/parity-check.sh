#!/usr/bin/env bash
# usage: parity-check.sh <xml-dir-A> <xml-dir-B> [logA] [logB]
# Sums JUnit XML testsuite counts (tests/failures/errors/skipped) per dir and compares
# arm-vs-arm; scans XMLs and optional arm logs for OOM signatures.
# exit 0 = parity AND 0 failures AND 0 OOM; 1 = mismatch/failures/OOM; 2 = VOID (missing/empty input).
# VOID is never a pass: an empty results dir must exit 2, not report 0==0.
# Fail-direction run recorded in parity-check.FAIL-DIRECTION.txt beside this file.
set -u
A="${1:?dir A required}"; B="${2:?dir B required}"; LA="${3:-}"; LB="${4:-}"
sum_dir() {
  local d="$1"
  local files
  files=$(find "$d" -maxdepth 1 -name 'TEST-*.xml' 2>/dev/null)
  [ -z "$files" ] && return 2
  # shellcheck disable=SC2086
  sed -n 's/.*<testsuite [^>]*tests="\([0-9]*\)"[^>]*skipped="\([0-9]*\)"[^>]*failures="\([0-9]*\)"[^>]*errors="\([0-9]*\)".*/\1 \2 \3 \4/p' $files \
    | awk '{t+=$1; s+=$2; f+=$3; e+=$4; n++} END {if (n==0) exit 2; print t, s, f, e, n}'
}
outA=$(sum_dir "$A"); rcA=$?
outB=$(sum_dir "$B"); rcB=$?
if [ $rcA -ne 0 ] || [ $rcB -ne 0 ] || [ -z "$outA" ] || [ -z "$outB" ]; then
  echo "VOID: unreadable/empty results dir (A rc=$rcA '$outA' | B rc=$rcB '$outB')"; exit 2
fi
read -r tA sA fA eA nA <<< "$outA"
read -r tB sB fB eB nB <<< "$outB"
echo "A: tests=$tA skipped=$sA failures=$fA errors=$eA suites=$nA   ($A)"
echo "B: tests=$tB skipped=$sB failures=$fB errors=$eB suites=$nB   ($B)"
bad=0
[ "$tA" = "$tB" ] && [ "$sA" = "$sB" ] && [ "$fA" = "$fB" ] && [ "$eA" = "$eB" ] && [ "$nA" = "$nB" ] \
  || { echo "PARITY MISMATCH"; bad=1; }
[ "$fA" = "0" ] && [ "$eA" = "0" ] && [ "$fB" = "0" ] && [ "$eB" = "0" ] \
  || { echo "FAILURES/ERRORS PRESENT"; bad=1; }
for src in "$A" "$B" $LA $LB; do
  [ -e "$src" ] || continue
  hits=$(grep -rlE "OutOfMemoryError|unable to create.*native thread" "$src" 2>/dev/null | wc -l)
  [ "$hits" -gt 0 ] && { echo "OOM SIGNATURE in $src ($hits files)"; bad=1; }
done
[ $bad -eq 0 ] && echo "PARITY OK, 0 failures, 0 OOM"
exit $bad
