#!/usr/bin/env bash
# usage: run-arm.sh <arm-name> [extra gradle args...]
#   e.g. run-arm.sh serial   -PitMaxParallelForks=1
#        run-arm.sh parallel
#        run-arm.sh falsify2 -PitMaxParallelForks=2 --tests uk.jtoye.core.storefront.PublicApiVersionAliasIntegrationTest
# Runs :core-java:integrationTest --rerun (never trusts UP-TO-DATE — a cached task
# reports success while executing nothing), tees Gradle output to probes/arm-<name>.log,
# records epoch brackets + host load context to probes/arm-<name>.context, and archives
# the JUnit XML from the LIVE output dir core-java/build-local/ (core-java/build/ is a
# stale artifact dir) into evidence/<name>-test-results/.
# Fail-direction run recorded in probes/run-arm.FAIL-DIRECTION.txt.
set -u
ARM="${1:?arm name required}"; shift
REPO=/home/sanmi/IdeaProjects/JToye_OaaS_2026
RUN_DIR="$REPO/.qa-council/20260815-173801"
LOG="$RUN_DIR/probes/arm-$ARM.log"
CTX="$RUN_DIR/probes/arm-$ARM.context"
{
  echo "arm=$ARM"
  echo "gradle_args=$*"
  echo "start_epoch=$(date +%s)"
  echo "start_iso=$(date -Is)"
  echo "nproc=$(nproc)"
  echo "mem_mb=$(free -m | awk '/^Mem:/ {print "total="$2" used="$3" available="$7}')"
  echo "docker_running=$(docker ps -q | wc -l)"
  echo "loadavg=$(cut -d' ' -f1-3 /proc/loadavg)"
} > "$CTX"
cd "$REPO" || exit 3
./gradlew :core-java:integrationTest --rerun "$@" > "$LOG" 2>&1
rc=$?
{
  echo "end_epoch=$(date +%s)"
  echo "end_iso=$(date -Is)"
  echo "gradle_rc=$rc"
  echo "end_loadavg=$(cut -d' ' -f1-3 /proc/loadavg)"
} >> "$CTX"
mkdir -p "$RUN_DIR/evidence/$ARM-test-results"
cp -a "$REPO/core-java/build-local/test-results/integrationTest/." "$RUN_DIR/evidence/$ARM-test-results/" 2>/dev/null
exit $rc
