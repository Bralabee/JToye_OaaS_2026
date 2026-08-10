#!/usr/bin/env bash
# check-alert-corpus-parity.sh — "one alert corpus" made executable (DPLY-03 / D-16).
#
# WHY THIS EXISTS
#
#   D-16 says there is ONE rule corpus, mounted verbatim by both runtimes. kustomize
#   cannot help: a `configMapGenerator` may only read files INSIDE its own
#   kustomization root, so `../../infra/monitoring/prometheus/alerts.yml` is rejected
#   ("security; file is not in or below the current directory"), and a symlink is
#   resolved the same way. There is no supported escape.
#
#   So "one corpus" becomes a committed COPY plus this gate. Without the gate the
#   invariant is an intention, and the failure mode is the worst kind available in
#   this repo: the two files drift, both Prometheus instances keep evaluating rules,
#   every dashboard stays green, and the ONLY observable consequence is that an alert
#   somebody added in compose never fires in staging. Nothing goes red. Nobody looks.
#
#   That is the same shape as the defects the corpus itself was written about
#   (StompBrokerLag matching a series family with no `queue` label for months;
#   `pg_up` live and referenced by no rule) — a monitoring gap that is invisible
#   precisely because monitoring is what is broken.
#
# WHAT IT ASSERTS
#
#   md5(k8s/base/monitoring/alerts.yml) == md5(infra/monitoring/prometheus/alerts.yml)
#
#   BYTE-EQUALITY, deliberately, and for the same reason check-alert-liveness.sh's L-0
#   is byte-exact: any semantic comparison needs a normaliser, a normaliser that is
#   slightly wrong makes a REQUIRED gate cry wolf, and a checksum has no such failure
#   mode. It also catches comment-only drift — which matters here, because the
#   comments in that corpus carry the measurements that justify the thresholds.
#
# WHICH FILE IS THE SOURCE
#
#   `infra/monitoring/prometheus/alerts.yml` is CANONICAL. `k8s/base/monitoring/alerts.yml`
#   is the copy. When this gate fails, copy canonical -> k8s, never the other way,
#   unless the k8s side is where the intended edit was made — in which case copy it
#   back to canonical and RECREATE the compose Prometheus container, because a
#   single-file bind mount detaches on inode change and the running compose
#   Prometheus will otherwise keep serving the old bytes (check-alert-liveness.sh
#   L-0 exists for exactly that, and will VOID until the container is recreated):
#
#       cp infra/monitoring/prometheus/alerts.yml k8s/base/monitoring/alerts.yml
#       docker compose --env-file .env -f infra/monitoring/docker-compose.monitoring.yml \
#         up -d --force-recreate prometheus
#
#   THE COPY CARRIES NO HEADER OF ITS OWN, and that is not an oversight. A "this is a
#   copy" banner inside the copy would make the two files differ by construction and
#   this gate could never pass; a banner added to BOTH would change the canonical
#   file's md5 and VOID L-0 against every already-running compose Prometheus. The
#   statement therefore lives here, in k8s/base/kustomization.yaml beside the
#   generator, and in k8s/base/monitoring/prometheus-config.yaml.
#
# WHAT IT DOES NOT ASSERT
#
#   That the rules are CORRECT, that they reference live series, or that the running
#   Prometheus is serving these bytes. Those are check-alert-metrics.sh, L-1b and L-0
#   respectively. This gate answers exactly one question — are the two committed
#   copies the same file — and answering more would duplicate gates that already
#   exist and are stronger at it.
#
# EXIT CODES — uniform across this repo's gates
#   0 = the two corpora are byte-identical
#   1 = they have DRIFTED (both md5s and a unified diff are printed)
#   2 = VOID: either file is missing/unreadable, or md5sum is not on PATH
#
#   A missing input is never clean. That is the single most important line in this
#   file: the natural bug here is a `[ -f X ] || exit 0` skip, which would turn a
#   deleted corpus — the most complete drift possible — into a pass.
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || { echo "VOID: cannot cd to repo root $REPO_ROOT" >&2; exit 2; }

CANONICAL="${CANONICAL:-infra/monitoring/prometheus/alerts.yml}"
COPY="${COPY:-k8s/base/monitoring/alerts.yml}"

void() { echo "VOID: $*" >&2; exit 2; }

command -v md5sum >/dev/null 2>&1 || void "md5sum not on PATH — the comparison cannot be evaluated"
command -v diff   >/dev/null 2>&1 || void "diff not on PATH — a drift report could not be produced"

# Presence and readability are asserted for BOTH sides, separately, so the message
# names which one is missing. `-s` as well as `-r`: an EMPTY file is readable, would
# md5-match another empty file, and is not a corpus.
for f in "$CANONICAL" "$COPY"; do
    [ -e "$f" ] || void "alert corpus not found: $f — a missing input is never clean (exit 2, not 0)"
    [ -r "$f" ] || void "alert corpus not readable: $f"
    [ -s "$f" ] || void "alert corpus is EMPTY: $f — an empty file would md5-match another empty file"
done

# Capture on the SAME statement as the invocation. Reading $? after an echo or a pipe
# reports THAT command's status, which is 0 essentially always.
MD5_CANONICAL=$(md5sum -- "$CANONICAL" | awk '{print $1}'); rc_a=$?
MD5_COPY=$(md5sum -- "$COPY" | awk '{print $1}');           rc_b=$?
[ "$rc_a" -eq 0 ] && [ "$rc_b" -eq 0 ] \
    || void "md5sum failed (canonical rc=$rc_a, copy rc=$rc_b)"
[ -n "$MD5_CANONICAL" ] && [ -n "$MD5_COPY" ] \
    || void "md5sum produced empty output — unparseable, refusing to report clean"

LINES_CANONICAL=$(wc -l < "$CANONICAL")
LINES_COPY=$(wc -l < "$COPY")

if [ "$MD5_CANONICAL" = "$MD5_COPY" ]; then
    echo "check-alert-corpus-parity  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
    echo "  canonical : $CANONICAL  md5=$MD5_CANONICAL  lines=$LINES_CANONICAL"
    echo "  k8s copy  : $COPY  md5=$MD5_COPY  lines=$LINES_COPY"
    echo "PASS: one alert corpus — the two committed copies are byte-identical."
    exit 0
fi

{
    echo "FAIL: the alert corpus has DRIFTED. There is supposed to be exactly one (D-16)."
    echo "  canonical : $CANONICAL  md5=$MD5_CANONICAL  lines=$LINES_CANONICAL"
    echo "  k8s copy  : $COPY  md5=$MD5_COPY  lines=$LINES_COPY"
    echo
    echo "--- diff (canonical '-' vs k8s copy '+') ---"
    diff -u --label "$CANONICAL (CANONICAL)" --label "$COPY (K8S COPY)" \
        "$CANONICAL" "$COPY" || true
    echo
    echo "A rule that exists in only one of these fires in only one runtime, and NOTHING"
    echo "goes red to say so. Reconcile them — see this script's header for which"
    echo "direction to copy and why the compose container must then be recreated."
} >&2

exit 1
