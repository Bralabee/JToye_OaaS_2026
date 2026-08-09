#!/usr/bin/env bash
# check-openapi-snapshot-fresh.sh — the committed OpenAPI contract, checked against the
# RUNNING service.
#
# WHY THIS EXISTS
#
#   The agent-readiness contract requires the machine-readable spec to match live responses. A
#   hand-edited snapshot asserts a shape nothing produces, and nothing in this repository could
#   previously tell the difference: OpenApiSnapshotTest compares the committed file against a
#   spec generated from the SOURCE TREE inside Testcontainers. That answers "does the snapshot
#   match the code" — a real and useful question — but it is green whether or not the deployed
#   service was ever rebuilt from that code. This repository has already shipped a runtime
#   missing its own application.yml change past four green gates.
#
#   So the two gates ask different questions and both are needed:
#
#     OpenApiSnapshotTest (in CI's integrationTest)  contract <-> SOURCE TREE, exact
#     this script                                   contract <-> RUNNING SERVICE
#
# WHAT IS ASSERTED, AND WHY IN THIS FORM
#
#   A-1  SUBSUMPTION, not equality. Every key and value present in the committed snapshot must
#        be present, and equal, in the spec the running service emits. Implemented by PROJECTING
#        the live spec through the committed one's shape and diffing — so a failure prints the
#        offending path and both values, not a bare boolean.
#
#        Deliberately NOT byte equality. MEASURED, on 2026-08-09, against the previous committed
#        snapshot and the then-running container: the live spec carries two extra paths,
#        /dev/tenants/ensure and /health/security, because the compose service runs the `dev`
#        profile while the snapshot is generated under `test`. An equality gate would therefore
#        be PERMANENTLY RED on a perfectly correct tree — and this repository records that a
#        permanently-red required gate is worse than none, because it teaches people to add
#        `|| true`. The reverse direction is what matters here and it is asserted at full
#        strength: committed-only paths measured ZERO, and any that appear will fail A-1.
#
#   A-2  DENOMINATOR. Both specs must declare at least one path, and the committed file must be
#        non-empty. An empty document projects cleanly against anything — the exact vacuous shape
#        this gate exists to prevent. "Found nothing" is never "clean".
#
#   A-3  LIVE-ONLY PATHS ARE REPORTED, not failed. They are listed by name every run, so the
#        tolerance in A-1 is visible rather than hidden. A new endpoint appearing here that is
#        NOT profile-specific means the snapshot is stale and should be regenerated.
#
# NORMALISATION — applied IDENTICALLY to both sides, and each rule was forced by a measurement
#
#   servers                       stripped. Environment-dependent and synthesised from the
#                                 request; the in-repo Jackson normaliser already strips it.
#   numbers                       canonicalised via `. + 0`. jq 1.7 preserves number literals, so
#                                 Jackson's BigDecimal rendering of 90 as `9E+1` diffed against
#                                 springdoc's `90.0` — a false failure on identical semantics.
#   authorizationUrl, tokenUrl,   replaced with a constant on both sides. These are built from
#   refreshUrl, openIdConnectUrl  the OIDC issuer-uri, which differs between the test profile
#                                 (localhost:8085) and the container network (keycloak:8080) BY
#                                 DESIGN — the recorded split-horizon issuer configuration. They
#                                 are deployment configuration, not API contract.
#   tags (root array)             re-keyed into an OBJECT by tag name on both sides. The root tag
#                                 list is a SET whose membership depends on which controllers are
#                                 active, so comparing it positionally is wrong: the dev-profile
#                                 "Health" tag shifted the array and produced a cascade of
#                                 misleading positional diffs plus a phantom "extra element",
#                                 none of which was a contract difference. As an object it obeys
#                                 the same rule as paths — declared tags compared exactly,
#                                 live-only tags reported below.
#
#   Every one of these was found by running the diff, not predicted. They are listed here so a
#   later reader can see exactly how much the gate is choosing not to look at.
#
# EXIT CODES — uniform with the other ops gates
#   0 = the running service emits everything the contract claims
#   1 = it does not
#   2 = VOID: the question could not be answered
#
#   VOID on: curl/jq/diff missing · the service unreachable or not answering 200 · an empty or
#   unparseable fetch · a missing, empty or unparseable committed snapshot · zero paths on
#   either side.
#
# SHAPE RULES OBSERVED (each is a recorded failure in this repository)
#   - `rc` is captured on the SAME statement as its command. `$?` read after an intervening echo
#     reports the echo, which is 0 essentially always.
#   - No `cmd | grep -q X`: under pipefail that INVERTS on match via SIGPIPE -> 141.
#   - An empty result is never silently treated as a clean one.
#
# WHY THIS IS NOT IN CI
#   It needs a running core-java to curl. A GitHub-hosted runner has none, so this could only
#   ever exit 2 there. Declared in scripts/gates/gate-enforcement.conf with that reason.
#
# USAGE
#   bash scripts/check-openapi-snapshot-fresh.sh
#   OPENAPI_BASE_URL=http://localhost:9090 bash scripts/check-openapi-snapshot-fresh.sh
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

BASE_URL="${OPENAPI_BASE_URL:-http://localhost:9090}"
SPEC_PATH="${OPENAPI_SPEC_PATH:-/v3/api-docs}"
SNAPSHOT="${OPENAPI_SNAPSHOT:-docs/api/openapi-snapshot.json}"

VOID=2
FAIL=1

WORK=""
cleanup() { [ -n "$WORK" ] && rm -rf "$WORK"; }
trap cleanup EXIT

void() {
    echo "VOID: $*" >&2
    echo "  (a contract that could not be compared is never a clean contract)" >&2
    exit "$VOID"
}

echo "=============================================================================="
echo " check-openapi-snapshot-fresh — the committed contract vs the RUNNING service"
echo " service=$BASE_URL$SPEC_PATH   snapshot=$SNAPSHOT"
echo "=============================================================================="

# ---- Preconditions -----------------------------------------------------------------------

for tool in curl jq diff; do
    command -v "$tool" >/dev/null 2>&1 || void "$tool is not on PATH — nothing can be compared"
done

[ -f "$SNAPSHOT" ] || void "committed snapshot '$SNAPSHOT' does not exist.
  Generate it with ./gradlew :core-java:updateOpenApiSnapshot and commit it."
[ -s "$SNAPSHOT" ] || void "committed snapshot '$SNAPSHOT' is EMPTY"

WORK=$(mktemp -d); rc=$?
[ "$rc" -eq 0 ] && [ -n "$WORK" ] || void "could not create a temporary directory (rc=$rc)"

# ---- Fetch the spec the RUNNING service actually emits -------------------------------------

HTTP_CODE=$(curl -sS --max-time 30 -o "$WORK/live-raw.json" -w '%{http_code}' "$BASE_URL$SPEC_PATH" 2>"$WORK/curl.err"); rc=$?
if [ "$rc" -ne 0 ]; then
    void "could not reach $BASE_URL$SPEC_PATH (curl rc=$rc): $(cat "$WORK/curl.err" 2>/dev/null)
  The stack is down or the port is wrong. This is the arm that stops the gate reporting a
  clean contract against a service it never talked to."
fi
[ "$HTTP_CODE" = "200" ] || void "$BASE_URL$SPEC_PATH answered HTTP $HTTP_CODE, not 200"
[ -s "$WORK/live-raw.json" ] || void "the running service returned an EMPTY body.
  An empty document projects cleanly against anything — refusing to report a pass."

# ---- Normalisation, applied identically to both sides --------------------------------------

cat > "$WORK/canon.jq" <<'JQ'
def canon:
  del(.servers)
  | walk(if type == "number" then . + 0 else . end)
  | walk(if type == "object"
         then reduce (["authorizationUrl","tokenUrl","refreshUrl","openIdConnectUrl"][]) as $k
                (.; if has($k) then .[$k] = "<<environment-dependent: normalised on both sides>>" else . end)
         else . end)
  | if (.tags | type) == "array"
    then .tags |= (map({key: (.name // "?"), value: .}) | from_entries)
    else . end;
canon
JQ

jq -S --indent 2 -f "$WORK/canon.jq" "$WORK/live-raw.json" > "$WORK/live.json" 2>"$WORK/jq.err"; rc=$?
[ "$rc" -eq 0 ] || void "the running service's spec is not parseable JSON (jq rc=$rc): $(head -3 "$WORK/jq.err" 2>/dev/null)"

jq -S --indent 2 -f "$WORK/canon.jq" "$SNAPSHOT" > "$WORK/committed.json" 2>"$WORK/jq.err"; rc=$?
[ "$rc" -eq 0 ] || void "the committed snapshot is not parseable JSON (jq rc=$rc): $(head -3 "$WORK/jq.err" 2>/dev/null)"

# ---- A-2: denominators ----------------------------------------------------------------------

COMMITTED_PATHS=$(jq -r '(.paths // {}) | keys | length' "$WORK/committed.json"); rc=$?
[ "$rc" -eq 0 ] && [ -n "$COMMITTED_PATHS" ] || void "could not count paths in the committed snapshot"
LIVE_PATHS=$(jq -r '(.paths // {}) | keys | length' "$WORK/live.json"); rc=$?
[ "$rc" -eq 0 ] && [ -n "$LIVE_PATHS" ] || void "could not count paths in the running service's spec"

echo
echo "A-2  denominators (both must be >= 1; a zero makes A-1 vacuous)"
echo "  paths declared by the committed contract ..... $COMMITTED_PATHS"
echo "  paths emitted by the running service ......... $LIVE_PATHS"

[ "$COMMITTED_PATHS" -ge 1 ] || void "A-2: the committed snapshot declares ZERO paths"
[ "$LIVE_PATHS" -ge 1 ] || void "A-2: the running service emits ZERO paths"

# ---- A-3: live-only paths, reported --------------------------------------------------------

LIVE_ONLY=$(jq -r --slurpfile c "$WORK/committed.json" '((.paths // {}) | keys) - (($c[0].paths // {}) | keys) | .[]' "$WORK/live.json"); rc=$?
[ "$rc" -eq 0 ] || void "could not compute the live-only path set (jq rc=$rc)"

echo
echo "A-3  paths the RUNNING service has that the contract does not (reported, not failed)"
if [ -z "$LIVE_ONLY" ]; then
    echo "  (none)"
else
    while IFS= read -r p; do echo "    $p"; done <<< "$LIVE_ONLY"
    echo "  Expected for profile-specific surfaces (the compose service runs 'dev', the snapshot"
    echo "  is generated under 'test'). Anything else here means the snapshot is STALE —"
    echo "  regenerate with ./gradlew :core-java:updateOpenApiSnapshot and commit it."
fi

LIVE_ONLY_TAGS=$(jq -r --slurpfile c "$WORK/committed.json" '((.tags // {}) | keys) - (($c[0].tags // {}) | keys) | .[]' "$WORK/live.json"); rc=$?
[ "$rc" -eq 0 ] || void "could not compute the live-only tag set (jq rc=$rc)"
echo
echo "     tags the RUNNING service has that the contract does not (reported, not failed)"
if [ -z "$LIVE_ONLY_TAGS" ]; then
    echo "       (none)"
else
    while IFS= read -r t; do echo "       $t"; done <<< "$LIVE_ONLY_TAGS"
fi

# ---- A-1: project the live spec through the contract's shape and diff -----------------------

cat > "$WORK/project.jq" <<'JQ'
def project($tmpl; $live):
  if ($tmpl | type) == "object" then
    if ($live | type) != "object" then
      "<<TYPE MISMATCH: the running service has \($live | type) where the contract declares an object>>"
    else
      reduce ($tmpl | keys_unsorted[]) as $k ({};
        .[$k] = (if ($live | has($k))
                 then project($tmpl[$k]; $live[$k])
                 else "<<ABSENT FROM THE RUNNING SERVICE>>" end))
    end
  elif ($tmpl | type) == "array" then
    if ($live | type) != "array" then
      "<<TYPE MISMATCH: the running service has \($live | type) where the contract declares an array>>"
    else
      [ range(0; $tmpl | length) as $i
          | (if $i < ($live | length)
             then project($tmpl[$i]; $live[$i])
             else "<<ABSENT FROM THE RUNNING SERVICE>>" end) ]
      + (if ($live | length) > ($tmpl | length)
         then [ "<<\(($live | length) - ($tmpl | length)) EXTRA ELEMENT(S) IN THE RUNNING SERVICE>>" ]
         else [] end)
    end
  else $live end;

project($tmpl[0]; $live[0])
JQ

jq -n -S --indent 2 \
    --slurpfile tmpl "$WORK/committed.json" \
    --slurpfile live "$WORK/live.json" \
    -f "$WORK/project.jq" > "$WORK/projected.json" 2>"$WORK/jq.err"; rc=$?
[ "$rc" -eq 0 ] || void "the projection failed (jq rc=$rc): $(head -3 "$WORK/jq.err" 2>/dev/null)"
[ -s "$WORK/projected.json" ] || void "the projection produced an EMPTY document"

diff -u "$WORK/committed.json" "$WORK/projected.json" > "$WORK/contract.diff"; rc=$?

echo
echo "A-1  every key the contract declares, as the running service emits it"
if [ "$rc" -eq 0 ]; then
    echo "  PASS — 0 differences across $COMMITTED_PATHS declared path(s)."
    echo
    echo "------------------------------------------------------------------------------"
    echo "RESULT: PASS — the running service emits everything the committed contract claims."
    exit 0
fi

if [ "$rc" -gt 1 ]; then
    void "diff itself failed (rc=$rc) — the comparison did not happen"
fi

echo "  FAIL — the running service does not match the committed contract." >&2
echo "  '-' is what docs/api/openapi-snapshot.json claims; '+' is what the service emits." >&2
echo >&2
sed -n '1,200p' "$WORK/contract.diff" >&2
DIFF_LINES=$(grep -c '' "$WORK/contract.diff"); rc=$?
[ "$rc" -eq 0 ] && [ "$DIFF_LINES" -gt 200 ] && echo "  ... ($DIFF_LINES diff lines in total, truncated at 200)" >&2
echo >&2
echo "  Either the snapshot was hand-edited, or the running image predates the branch." >&2
echo "  Regenerate with ./gradlew :core-java:updateOpenApiSnapshot, then REBUILD the service" >&2
echo "  — docker compose start does not rebuild." >&2
echo "------------------------------------------------------------------------------" >&2
echo "RESULT: FAIL" >&2
exit "$FAIL"
