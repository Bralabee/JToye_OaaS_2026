#!/usr/bin/env bash
# check-alert-liveness.sh — proves the monitoring can actually SEE and TELL.
#
# WHY THIS EXISTS (Phase 27, plan 27-00 — findings F-1, F-3, F-3b, F-3c, F-4)
#
#   Measured on this repo on 2026-07-27, before any fix: 11 of 14 alert rules
#   were defective and every one of them reported health=ok. Syntax validity is
#   not liveness. The four ways a rule can be green and blind:
#
#     F-3   Six rules were dataless because the core-java scrape target had been
#           DOWN since the port moved. `up{job="core-java"} = 0`.
#     F-3b  DatabaseDown watches `up{job="postgres"}`, which was 1 because the
#           EXPORTER answered — while `pg_up` was 0 and not one PostgreSQL metric
#           was being collected. A target being UP is not evidence the thing
#           behind it is healthy.
#     F-3c  HighMemoryUsage and FrequentGarbageCollection carry
#           `service: core-java` but their selectors are unqualified, so they
#           bound to the only JVM Prometheus could see: KEYCLOAK's. These are
#           worse than dataless — a naive "selector matches >= 1 series" check
#           reports them GREEN while they watch the wrong process.
#     F-1   StompBrokerLag selects `{queue=~...}` against an aggregated broker
#           endpoint that emits no `queue` label at all. It matches 0 series,
#           can never fire, and reports health=ok.
#
#   Each assertion below pins one of those.
#
# THE SIX ASSERTIONS
#
#   L-0   SOURCE PARITY. The alerts file this gate reads is byte-identical to the
#         one the running Prometheus is serving. Without this the gate mixes two
#         sources — L-1b greps the FILE, L-2/L-2b query the running rules via the
#         API — and can report a verdict that is true of neither. See below.
#   L-1   Every scrape target reports health == "up".
#   L-1b  For every exporter-backed job, its SELF-REPORTED upstream gauge is 1
#         AND that gauge name appears in at least one alerts.yml expression.
#         Both halves are required: a gauge at 1 that no rule reads is not
#         detection, and a rule reading a gauge that is 0 is the live defect.
#   L-2   Every rule's metric selector matches >= 1 live series.
#   L-2b  SUBJECT CORRECTNESS. A rule labelled `service: X` must match at least
#         one series from the JOB that X maps to. This is the assertion the
#         drafted gate lacked and the reason F-3c survived review.
#   L-3   TRANSPORT. A synthetic alert posted to Alertmanager actually arrives
#         at the configured destination.
#   L-3b  SINK CO-RESIDENCY. The inspectable sink L-3 searches sits in the SAME
#         receiver as the real human destination, so L-3's observation is
#         evidence about the human's leg and not about a side channel.
#
#   WHAT L-3 PROVES AND DOES NOT PROVE: it proves the message left Alertmanager
#   and arrived at the configured sink; it does NOT prove a human reads that
#   sink, and in compose that sink is Mailhog at ops@jtoye.local with no human
#   behind it. A real ServiceDown fired here for over 46 hours and reached
#   nobody. Do not report a green L-3 as "operators are now notified".
#
# WHY L-3b EXISTS — and the measurement that made it necessary (phase 29, D-17)
#
#   Staging's real alert destination is a Gmail inbox, which has no HTTP API to
#   search. The fix is NOT to give the probe its own route: the header below
#   already rejects that shape in prose, and prose does not survive. The fix is
#   that the ONE receiver carries TWO email_configs entries — the Gmail relay and
#   an in-cluster Mailhog — so the same route, the same grouping and the same
#   template carry both, and inspecting the Mailhog leg is evidence about the
#   Gmail leg.
#
#   That only works if Alertmanager fans out to EVERY email_configs entry in a
#   receiver rather than stopping at the first. Research recorded this as
#   assumption A1 at LOW/MEDIUM confidence, reasoned from the config schema being
#   a list. It was MEASURED on 2026-08-10 instead, on an isolated
#   prom/alertmanager:v0.27.0 (identical image to the live stack, same Mailhog
#   smarthost, live stack not mutated):
#
#     ARM 1  one receiver, TWO email_configs, different `to:` -> ONE alert posted
#            GET /api/v2/search?kind=containing&query=a1probe-1786394841-3233795
#              total = 2
#              a1-primary@jtoye.local   = 1
#              a1-secondary@jtoye.local = 1
#
#     ARM 2  the control. IDENTICAL rig, ONE email_configs entry
#            GET /api/v2/search?kind=containing&query=a1control-1786394888-3236713
#              total = 1
#              a1-primary@jtoye.local   = 1
#              a1-secondary@jtoye.local = 0
#
#   Arm 2 is what makes arm 1 mean anything: it proves the per-recipient counter
#   CAN return 0, so the 1/1 above is a real fan-out and not an artefact of the
#   search matching one message twice. A1 HOLDS.
#
#   L-3b is the executable form of the invariant that measurement unlocked. The
#   dual-sink receiver is only honest while both entries stay in the SAME
#   receiver; the moment someone splits them, L-3 would go green having proved
#   nothing about the inbox a human reads. A gate that depends on a config shape
#   must assert that shape.
#
# WHY THIS GATE IS NOT IN CI — and what replaces it
#
#   Same reasoning as check-runtime-freshness.sh: a CI runner has no Prometheus,
#   no Alertmanager and no Mailhog, so this could only ever exit 2 there. A
#   permanently-VOID job trains people to add `|| true`, which is worse than no
#   job. The enforcement instead: this script's EXIT CODE and an ISO-8601
#   timestamp are a REQUIRED FIELD in every phase SUMMARY and a line on the
#   phase-close checklist. A phase that cannot show a run has not shown its
#   monitoring works.
#
# EXIT CODES — uniform across this plan's gates
#   0 = clean · 1 = a live detection defect · 2 = VOID (could not evaluate)
#
#   VOID on: missing curl/jq/python3, a missing PROM_EXEC runtime binary (docker
#   by default, kubectl in k8s), an absent exec target, unreachable Prometheus or
#   Alertmanager, ZERO targets or ZERO rules discovered, an unparseable
#   expression, an exporter job with no gauge mapping, an unmapped `service:`
#   label, an unreadable or multi-destination-but-undeclared alert receiver, or a
#   destination that cannot be inspected. "Found nothing" is NEVER "clean", and an
#   unmapped new thing must stop the gate rather than skip.
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

PROM_URL="${PROM_URL:-http://localhost:9091}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"
ALERTS="${ALERTS:-infra/monitoring/prometheus/alerts.yml}"
# L-0 needs to read the file out of the running Prometheus. These are overridable
# because none of them is a property of this script — a k8s Prometheus has a
# different container name, a different in-container path, and is not reached with
# docker at all. PROM_EXEC / PROM_RUNTIME_PROBE are declared at the L-0 block
# below, next to the reasoning for their defaults; a k8s invocation looks like:
#
#   PROM_EXEC="kubectl --context <ctx> -n <ns> exec deploy/prometheus --" \
#   PROM_RUNTIME_PROBE="kubectl --context <ctx> -n <ns> get deploy/prometheus" \
#   PROM_ALERTS_PATH=/etc/prometheus/alerts.yml \
#   PROM_URL=... ALERTMANAGER_URL=... MAILHOG_URL=... bash scripts/check-alert-liveness.sh
PROM_CONTAINER="${PROM_CONTAINER:-jtoye-prometheus}"
PROM_ALERTS_PATH="${PROM_ALERTS_PATH:-/etc/prometheus/alerts.yml}"

# L-3b needs to know which of the receiver's destinations is the one this gate can
# SEARCH, and which is the one a human READS. In compose they are the same single
# address and both may stay unset. In staging they differ (D-17: Gmail for the
# human, in-cluster Mailhog for this gate) and BOTH must be declared — see the
# L-3b block for why an undeclared multi-destination receiver is VOID rather than
# a guess.
L3_SINK_TO="${L3_SINK_TO:-}"
L3_HUMAN_TO="${L3_HUMAN_TO:-}"

void() { echo "VOID: $*" >&2; exit 2; }
VIOLATIONS=0
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }

# ---- data block: exporter job -> its self-reported upstream-health gauge ------
# Generalised deliberately. 27-00-PLAN.md's AC-4.2 names only postgres; register
# row TS-15 records why that is not enough — RedisDown has the IDENTICAL defect
# (it watches up{job="redis"}, while redis_up is live and referenced by no rule).
# Measured 2026-07-27: pg_up 0 rule refs, redis_up 0 rule refs. A gate that
# checked only postgres would ship blind to half the class it exists to catch.
# A new exporter job with no row here is VOID, so the mapping cannot fall behind.
EXPORTER_GAUGES=(
  "postgres|pg_up"
  "redis|redis_up"
)
# Jobs that are not exporter-fronted (they expose their own metrics directly).
# `rabbitmq-queues` (plan 27-03) is DIRECT, not exporter-fronted, and that distinction is the
# whole reason it belongs here rather than in EXPORTER_GAUGES above. Verified, not assumed: it
# targets the IDENTICAL endpoint as `rabbitmq` — jtoye-rabbitmq:15692 — differing only by
# metrics_path (`/metrics` vs `/metrics/detailed` with family params). It is the broker's own
# Prometheus plugin on a second path, not a sidecar exporter in front of a separate upstream, so
# there is no "self-reported upstream health" gauge for it to have. That concept earns its keep
# for postgres->pg_up and redis->redis_up, where the exporter can answer 200 while the thing
# behind it is dead; a second path on the same process cannot exhibit that failure.
#
# It was missing until 2026-07-29 (issue #339), so L-1b VOIDed on a correct tree and this gate
# could not run at all. That is the mechanism working: it refuses to silently skip a job it does
# not recognise. Do NOT "fix" a future VOID by teaching L-1b to ignore unknown jobs — that would
# be strictly worse than the red, because the whole point is that a new exporter cannot slip in
# unmapped.
DIRECT_JOBS=("prometheus" "core-java" "edge-go" "keycloak" "rabbitmq" "rabbitmq-queues")

# ---- data block: rule `service:` label -> Prometheus `job` --------------------
# RULE-LABEL -> JOB, never label -> label. prometheus.yml labels the core-java
# TARGET `service: core-api` while the RULE's own label says `core-java`; mapping
# label-to-label would silently never match. `*` means "not job-scoped".
SERVICE_JOB_MAP=(
  "core-java|core-java"
  "postgresql|postgres"
  "redis|redis"
  "keycloak|keycloak"
  # COMMA-SEPARATED: one service may legitimately be scraped by MORE THAN ONE job.
  # The broker is the live case — `rabbitmq` (node-level /metrics) and `rabbitmq-queues`
  # (per-queue /metrics/detailed, added by 27-03) are the SAME process on the SAME
  # endpoint, so a rule labelled service=rabbitmq is correct whichever of the two its
  # selector lands on. Before this was a list, the four per-queue rules
  # (PaymentDeadLetterQueueNonEmpty, DeadLetterQueueNonEmpty, DomainQueueBacklog,
  # MessagingConsumerMissing) were ALL reported wrong-subject — a FALSE POSITIVE from
  # the same root cause as #339: 27-00 data blocks predate 27-03 second scrape job.
  "rabbitmq|rabbitmq,rabbitmq-queues"
  "platform|*"
)

lookup() { # lookup <key> <array-name...>  -> prints value or empty
  local key="$1"; shift
  local row
  for row in "$@"; do
    [ "${row%%|*}" = "$key" ] && { echo "${row##*|}"; return 0; }
  done
  return 1
}

for t in curl jq python3; do
  command -v "$t" >/dev/null 2>&1 || void "$t not on PATH"
done
[ -r "$ALERTS" ] || void "alerts file not readable: $ALERTS"

curl -sf --max-time 10 "$PROM_URL/-/healthy" >/dev/null 2>&1 \
  || void "Prometheus unreachable at $PROM_URL — a stopped stack is VOID, never clean"

# ================================================================== L-0
# SOURCE PARITY — the file this gate reads vs the file the running Prometheus serves.
#
# WHY (added 2026-07-29). $ALERTS is bind-mounted into the Prometheus container AS A
# SINGLE FILE. A single-file bind mount is resolved to an inode at container start, so
# ANY change of inode detaches it — editing the file, and `git switch` too. The container
# then keeps serving the OLD content, and SIGHUP does not fix it: Prometheus logs
# `Completed loading of configuration file` for the stale content, which reads exactly
# like success.
#
# This gate is the one that mixes the two sources, so it is the one that must not be
# fooled: L-1b greps the FILE for a gauge reference, while L-2 and L-2b evaluate the
# rules the API reports. When those disagree the gate can return a verdict that is true
# of neither tree nor runtime. Measured on this repo on 2026-07-29 mid-session: host
# inode 18221918 vs container 18219239, different md5, the container serving the rules
# of a branch that was not checked out — and the gate exited 0 by combining one branch's
# file with another branch's runtime.
#
# It was documented as a manual pre-flight step in the session handoff ("ALWAYS check
# before trusting any alert-rule verification"). It fired anyway, on the next session,
# within the first ten minutes. A recurring failure that survives a written warning is
# owed an executable check.
#
# BYTE-EXACT, deliberately. The obvious alternative — parse both sides and compare rule
# names or normalised exprs — cannot be made reliable: the API returns Prometheus's own
# re-rendering, which reorders label matchers, so any comparison needs a normaliser, and
# a normaliser that is slightly wrong makes a REQUIRED phase-close gate cry wolf. A
# checksum has no such failure mode. It also catches comment-only drift, which a
# semantic compare would wave through even though it means the mount is detached and the
# NEXT edit will be invisible too.
#
# VOID, not FAIL. A gate whose two inputs disagree has not detected a monitoring defect;
# it has failed to evaluate. Recreating the container is the fix:
#   docker compose --env-file .env -f infra/monitoring/docker-compose.monitoring.yml \
#     up -d --force-recreate prometheus
#
# DO NOT REWRITE THIS WITH `docker cp`. It is the natural first reach and it CANNOT see
# this drift. Measured on the drifted stack, same container, same path, same moment:
#   docker cp   jtoye-prometheus:/etc/prometheus/alerts.yml -   md5 1cc20a85  <- the HOST file
#   docker exec jtoye-prometheus md5sum /etc/prometheus/alerts.yml  md5 d25f3d10  <- what it SERVES
# `docker cp` resolves the bind mount back to its source path on the host, so it reports
# the current host inode and the two sides always agree. It would turn this assertion
# into one that can never fail — the exact defect class the gate exists to catch.
# `docker exec` runs inside the container's mount namespace and sees the attached inode,
# which is what the Prometheus process actually reads. Use exec.
#
# THE SAME ASSERTION, A DIFFERENT RUNTIME (phase 29, Blocker B)
#
#   DPLY-03's criterion is that this gate exits 0 against the STAGING target, which is a
#   Kubernetes Prometheus. Until 2026-08-10 the three calls above were unconditionally
#   `docker`, and every failure path here is `void` — so against k8s this exited 2, which
#   this script's own doctrine says is never clean. A phase SUMMARY recording that exit 2
#   as "environment not ready" would be recording the criterion FAILING.
#
#   The fix is an env-selected exec, defaulting to the docker form so compose behaviour is
#   byte-identical:
#     PROM_EXEC           default: docker exec <PROM_CONTAINER>
#                         k8s:     kubectl --context <ctx> -n <ns> exec deploy/prometheus --
#     PROM_RUNTIME_PROBE  default: docker inspect <PROM_CONTAINER>
#                         k8s:     kubectl --context <ctx> -n <ns> get deploy/prometheus
#   and the docker-on-PATH check becomes a check on the FIRST WORD of PROM_EXEC, so a
#   kubectl-only host is not VOIDed for lacking a docker daemon it does not need.
#
#   WHAT L-0 CATCHES IN K8S IS NOT WHAT IT CATCHES IN COMPOSE, and both are real. A
#   ConfigMap is projected as a DIRECTORY OF SYMLINKS which the kubelet re-points
#   atomically on update, so the single-file inode-detach failure above simply does not
#   exist there — do not go looking for it. What does exist is a running pod serving an
#   OLDER ConfigMap version than the one just applied: the kubelet syncs on its own period
#   (up to ~1 minute plus cache TTL), and Prometheus then needs a reload it does not have
#   enabled in this repo's config, so the projected file can be current while the process
#   still holds the previous rules. Either way the served bytes differ from the tree's, and
#   a byte-exact md5 of what the PROCESS reads catches both. Keep the md5.
#
#   NEVER `kubectl cp`. It is the k8s analogue of the `docker cp` blindness measured above:
#   cp reads the container filesystem through the API and can disagree with what the
#   process has open, which is precisely the disagreement this assertion exists to find.
PROM_EXEC="${PROM_EXEC:-docker exec $PROM_CONTAINER}"
PROM_RUNTIME_PROBE="${PROM_RUNTIME_PROBE:-docker inspect $PROM_CONTAINER}"
read -r -a PROM_EXEC_ARGV <<<"$PROM_EXEC"
read -r -a PROM_PROBE_ARGV <<<"$PROM_RUNTIME_PROBE"
[ "${#PROM_EXEC_ARGV[@]}" -gt 0 ] \
  || void "L-0 PROM_EXEC is empty — there is no command with which to read the served alerts file"
[ "${#PROM_PROBE_ARGV[@]}" -gt 0 ] \
  || void "L-0 PROM_RUNTIME_PROBE is empty — the target's existence would go unchecked, and 'exec failed' would be reported as drift"

command -v "${PROM_EXEC_ARGV[0]}" >/dev/null 2>&1 \
  || void "L-0 '${PROM_EXEC_ARGV[0]}' (the first word of PROM_EXEC) is not on PATH, so the running Prometheus's own copy of $ALERTS cannot be read. Unverifiable parity is VOID, never clean — set PROM_EXEC/PROM_RUNTIME_PROBE/PROM_ALERTS_PATH for this runtime."
command -v "${PROM_PROBE_ARGV[0]}" >/dev/null 2>&1 \
  || void "L-0 '${PROM_PROBE_ARGV[0]}' (the first word of PROM_RUNTIME_PROBE) is not on PATH"

# Existence probe, by NAME. Container-not-found and deployment-not-found must BOTH still
# exit 2 here rather than being reported below as a checksum mismatch.
"${PROM_PROBE_ARGV[@]}" >/dev/null 2>&1 \
  || void "L-0 target not found — '$PROM_RUNTIME_PROBE' failed, so the served alerts file cannot be compared against $ALERTS (override PROM_RUNTIME_PROBE/PROM_EXEC for this runtime)"

HOST_SUM=$(md5sum "$ALERTS" | awk '{print $1}')
CTR_SUM=$("${PROM_EXEC_ARGV[@]}" md5sum "$PROM_ALERTS_PATH" 2>/dev/null | awk '{print $1}') \
  || void "L-0 cannot read $PROM_ALERTS_PATH via '$PROM_EXEC'"
[ -n "$CTR_SUM" ] \
  || void "L-0 empty checksum from '$PROM_EXEC md5sum $PROM_ALERTS_PATH' — an unreadable served file is VOID, never clean"

if [ "$HOST_SUM" != "$CTR_SUM" ]; then
  void "L-0 SOURCE DRIFT — the running Prometheus is NOT serving the file this gate reads.
       $ALERTS  md5=$HOST_SUM  inode=$(stat -c %i "$ALERTS")
       served via '$PROM_EXEC' at $PROM_ALERTS_PATH  md5=$CTR_SUM  inode=$("${PROM_EXEC_ARGV[@]}" stat -c %i "$PROM_ALERTS_PATH" 2>/dev/null || echo '?')
       Every result below would mix this tree's file with a different tree's runtime.
       COMPOSE: the single-file bind mount has detached; SIGHUP will NOT fix it — recreate:
         docker compose --env-file .env -f infra/monitoring/docker-compose.monitoring.yml up -d --force-recreate prometheus
       KUBERNETES: the pod is serving an older ConfigMap version than the one applied.
       Wait out the kubelet sync, then restart the workload — a ConfigMap edit alone does
       not reload a Prometheus without --web.enable-lifecycle:
         kubectl -n <ns> rollout restart deploy/prometheus"
fi

# ================================================================== L-1
TARGETS=$(curl -sf --max-time 15 "$PROM_URL/api/v1/targets?state=any") \
  || void "cannot read $PROM_URL/api/v1/targets"
TARGET_N=$(jq -r '.data.activeTargets | length' <<<"$TARGETS")
[ "${TARGET_N:-0}" -gt 0 ] || void "ZERO scrape targets discovered — the API shape changed or Prometheus has no config"

L1_DOWN=0
while IFS=$'\t' read -r job health url err; do
  [ -z "$job" ] && continue
  if [ "$health" != "up" ]; then
    violation "L-1 target job='$job' health=$health url=$url lastError=${err:-<none>}"
    L1_DOWN=$((L1_DOWN + 1))
  fi
done < <(jq -r '.data.activeTargets[] | [.labels.job, .health, .scrapeUrl, (.lastError // "")] | @tsv' <<<"$TARGETS")

JOBS=$(jq -r '.data.activeTargets[].labels.job' <<<"$TARGETS" | sort -u)

# ================================================================== L-1b
promq() { # promq <expr> -> first sample value, or empty
  curl -sfG --max-time 15 "$PROM_URL/api/v1/query" --data-urlencode "query=$1" \
    | jq -r '.data.result[0].value[1] // ""'
}

L1B_BLIND=0
L1B_UNREAD=0
while IFS= read -r job; do
  [ -z "$job" ] && continue
  gauge=$(lookup "$job" "${EXPORTER_GAUGES[@]}") || gauge=""
  if [ -z "$gauge" ]; then
    # not an exporter job? it must be on the DIRECT list, else the mapping is stale
    direct=0
    for d in "${DIRECT_JOBS[@]}"; do [ "$d" = "$job" ] && direct=1; done
    [ "$direct" = "1" ] || void "L-1b job '$job' has no upstream-gauge mapping and is not on DIRECT_JOBS — add a row rather than letting a new exporter skip the check"
    continue
  fi
  v=$(promq "$gauge")
  if [ "$v" != "1" ]; then
    violation "L-1b exporter job='$job' is UP but BLIND: $gauge = ${v:-<no series>} (the target answers; the thing behind it is not being measured)"
    L1B_BLIND=$((L1B_BLIND + 1))
  fi
  refs=$(command grep -c "$gauge" "$ALERTS" || true)
  if [ "$refs" -eq 0 ]; then
    violation "L-1b gauge '$gauge' (job='$job') is referenced by NO rule in $ALERTS — a healthy gauge nobody reads is not detection"
    L1B_UNREAD=$((L1B_UNREAD + 1))
  fi
done <<<"$JOBS"

# ================================================================== L-2 / L-2b
RULES=$(curl -sf --max-time 15 "$PROM_URL/api/v1/rules") || void "cannot read $PROM_URL/api/v1/rules"
RULE_N=$(jq -r '[.data.groups[].rules[] | select(.type=="alerting")] | length' <<<"$RULES")
[ "${RULE_N:-0}" -gt 0 ] || void "ZERO alerting rules discovered — Prometheus loaded no rule files"

# Extract metric selectors from a PromQL expression. python3 rather than a bash
# regex because getting this wrong SILENTLY under-reports, which is the failure
# mode this gate exists to prevent. PromQL keywords and function names are
# excluded by an explicit list; an expression yielding zero selectors is VOID,
# never a skip.
selectors_of() {
  python3 -c '
import re, sys
expr = sys.argv[1]
# Strip range/offset selectors FIRST: "[5m]" otherwise yields a bogus metric "m",
# and every rule using rate() would report a phantom 0-series selector. Then
# strip aggregation label lists: "by (service, le)" otherwise yields "service"
# and "le" as metrics. Both were observed as false positives on the real
# alerts.yml before this was added.
#
# The duration pattern is DELIBERATELY narrow. A generic r"\[[^\]]*\]" also eats
# character classes inside quoted label regexes — measured, it turned
# uri=~"/api/v[0-9]+/orders" into uri=~"/api/v+/orders" and
# queue=~"amq[.]gen-.*" into queue=~"amqgen-.*", silently querying a DIFFERENT
# selector than the rule uses and reporting that answer as if it were the rule
# result. Only a PromQL duration ([5m], [1h30m]) is stripped; "[0-9]" and "[.]"
# do not match. NB: no apostrophes in this block — it lives inside a
# single-quoted python3 -c string and one would terminate it.
expr = re.sub(r"\[\s*(?:[0-9]+(?:\.[0-9]+)?[smhdwy])+\s*\]", "", expr)
expr = re.sub(r"\b(by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)", " ", expr)
KW = {"by","without","on","ignoring","group_left","group_right","offset","bool","and","or","unless",
      "rate","irate","increase","sum","avg","min","max","count","count_values","stddev","stdvar",
      "topk","bottomk","quantile","histogram_quantile","delta","idelta","deriv","predict_linear",
      "abs","ceil","floor","round","clamp_max","clamp_min","exp","ln","log2","log10","sqrt",
      "time","timestamp","vector","scalar","absent","absent_over_time","changes","resets",
      "sum_over_time","avg_over_time","min_over_time","max_over_time","count_over_time",
      "label_replace","label_join","le","inf","nan"}
out, seen = [], set()
for m in re.finditer(r"([a-zA-Z_:][a-zA-Z0-9_:]*)\s*(\{[^}]*\})?", expr):
    name, labels = m.group(1), m.group(2) or ""
    if name in KW or name.isdigit(): continue
    sel = name + labels
    if sel not in seen:
        seen.add(sel); out.append(sel)
print("\n".join(out))
' "$1"
}

L2_EMPTY=0
L2B_WRONG=0
while IFS=$'\t' read -r name service query; do
  [ -z "$name" ] && continue
  mapped=$(lookup "$service" "${SERVICE_JOB_MAP[@]}") \
    || void "L-2b rule '$name' carries service='$service', which has no job mapping — add a row rather than letting a new label silently skip the check"

  sels=$(selectors_of "$query")
  [ -n "$sels" ] || void "L-2 could not extract any metric selector from rule '$name' expr: $query"

  rule_has_right_job=0
  while IFS= read -r sel; do
    [ -z "$sel" ] && continue
    n=$(curl -sfG --max-time 15 "$PROM_URL/api/v1/series" --data-urlencode "match[]=$sel" | jq -r '.data | length')
    bare="${sel%%\{*}"
    if [ "${n:-0}" -eq 0 ]; then
      nb=$(curl -sfG --max-time 15 "$PROM_URL/api/v1/series" --data-urlencode "match[]=$bare" | jq -r '.data | length')
      violation "L-2 rule '$name' selector '$sel' matches 0 series; without labels it matches ${nb:-0}"
      L2_EMPTY=$((L2_EMPTY + 1))
    elif [ "$mapped" != "*" ]; then
      hit=$(curl -sfG --max-time 15 "$PROM_URL/api/v1/series" --data-urlencode "match[]=$sel" \
            | jq -r --arg j "$mapped" '($j | split(",")) as $jobs | [.data[] | select(.job as $x | $jobs | index($x))] | length')
      [ "${hit:-0}" -gt 0 ] && rule_has_right_job=1
    fi
  done <<<"$sels"

  if [ "$mapped" != "*" ] && [ "$rule_has_right_job" -eq 0 ]; then
    jobs_seen=$(while IFS= read -r sel; do
                  [ -z "$sel" ] && continue
                  curl -sfG --max-time 15 "$PROM_URL/api/v1/series" --data-urlencode "match[]=$sel" \
                    | jq -r '.data[].job // empty'
                done <<<"$sels" | sort -u | paste -sd, - )
    violation "L-2b rule '$name' claims service=$service (job=$mapped) but its selector matches only job=${jobs_seen:-<none>}"
    L2B_WRONG=$((L2B_WRONG + 1))
  fi
done < <(jq -r '.data.groups[].rules[] | select(.type=="alerting") | [.name, (.labels.service // "platform"), .query] | @tsv' <<<"$RULES")

# ================================================================== L-3
curl -sf --max-time 10 "$ALERTMANAGER_URL/-/healthy" >/dev/null 2>&1 \
  || void "Alertmanager unreachable at $ALERTMANAGER_URL"
curl -sf --max-time 10 "$MAILHOG_URL/api/v2/messages?limit=1" >/dev/null 2>&1 \
  || void "destination (Mailhog at $MAILHOG_URL) is not inspectable — an unverifiable transport is VOID, never clean"

# TWO counters, not one. `_failed_total` alone cannot tell "Alertmanager tried and
# the SMTP hop failed" apart from "Alertmanager never tried at all" — BOTH leave it
# flat. That ambiguity is precisely what made issue #342 item 6 read as a flaky
# probe for two days: every observation was `0 -> 0`, which was reported as a
# delivery failure when in fact no delivery had ever been attempted.
#
# NOT a pipe into grep. `curl | grep -E ...` returns grep's exit status under
# `set -o pipefail`, so an Alertmanager that has not yet registered the email
# integration would abort the whole gate at the assignment with no message and no
# VOID. awk over a here-string with index()==1 (a literal prefix match, no regex
# escaping to get wrong) has neither failure mode.
am_counter() { # am_counter <metric name> -> value summed over email label sets, 0 if absent
  local out
  out=$(curl -sf --max-time 10 "$ALERTMANAGER_URL/metrics") || return 1
  awk -v m="$1" 'index($0, m "{integration=\"email\"") == 1 {s += $2} END {print s+0}' <<<"$out"
}
SENT_BEFORE=$(am_counter alertmanager_notifications_total) \
  || void "cannot read $ALERTMANAGER_URL/metrics"
FAILED_BEFORE=$(am_counter alertmanager_notifications_failed_total) \
  || void "cannot read $ALERTMANAGER_URL/metrics"

L3_TRIES=40
L3_SLEEP=3
L3_TIMEOUT=$((L3_TRIES * L3_SLEEP))

PROBE_BASE="SyntheticDeliveryProbe"
RUN_TOKEN="$(jq -rn 'now|floor|tostring')-$$"
PROBE="${PROBE_BASE}-${RUN_TOKEN}"
PROBE_ID="probe-${RUN_TOKEN}"

# ================================================================== L-3b
# SINK CO-RESIDENCY — is the sink this gate searches in the SAME receiver as the
# address a human reads?
#
# Read from the RUNNING Alertmanager (/api/v2/status -> .config.original), never
# from a file on disk: the file the gate can see and the config the process loaded
# are two different things, which is the same class of mistake L-0 exists for. The
# API returns Alertmanager's own re-marshalling, so the shape is normalised and
# predictable — measured on v0.27.0: top-level keys at column 0, the default
# route's receiver at exactly two spaces, `receivers:` items as `- name:` at
# column 0, and each email_configs entry's destination as a lowercase `to:`
# (the rendered SMTP header is the canonical-cased `To:` and is deliberately
# skipped by the parser below).
#
# THREE OUTCOMES, and the middle one is the point:
#   0 destinations  -> VOID. A receiver that mails nobody makes L-3 meaningless.
#   1 destination   -> the sink IS the destination. Nothing to co-reside with.
#   2+ destinations -> BOTH L3_SINK_TO and L3_HUMAN_TO must be declared and both
#                      must appear in that receiver. This is the staging shape,
#                      and refusing to guess is deliberate: a gate that picked one
#                      of several addresses on its own could search the leg nobody
#                      reads and report the human's inbox as proven.
#
# It also refuses a route built specially for the probe. The header above rejects
# that shape in words; this makes the words fire.
AM_CONFIG=$(curl -sf --max-time 10 "$ALERTMANAGER_URL/api/v2/status" | jq -r '.config.original // ""') \
  || void "L-3b cannot read $ALERTMANAGER_URL/api/v2/status — the running Alertmanager's own config is the only honest source for which receiver this probe traverses"
[ -n "$AM_CONFIG" ] \
  || void "L-3b $ALERTMANAGER_URL/api/v2/status returned no .config.original — an unreadable config is VOID, never clean"

# Prints: line 1 = the default route's receiver name, lines 2+ = that receiver's
# email destinations. python3 is already a hard dependency of this gate; PyYAML is
# deliberately NOT, because adding an import that may be absent would turn a
# REQUIRED phase-close gate into a VOID on hosts that are otherwise fine.
route_sink_block() {
  python3 -c '
import re, sys
# QUOTES are stripped via chr() rather than a literal, because this block lives
# inside a single-quoted python3 -c string and an apostrophe would terminate it.
# The same note guards the selectors_of block above; it is not decoration.
QUOTES = chr(34) + chr(39)
lines = sys.argv[1].splitlines()

receiver = ""
in_route = False
for ln in lines:
    if re.match(r"^route:\s*$", ln):
        in_route = True
        continue
    if in_route:
        if ln and not ln.startswith(" ") and not ln.startswith("-"):
            in_route = False
            continue
        # EXACTLY two spaces. A child route under `routes:` renders as
        # "  - receiver: x" and must not be mistaken for the default one.
        m = re.match(r"^ {2}receiver:\s*(\S.*)$", ln)
        if m:
            receiver = m.group(1).strip().strip(QUOTES)
            in_route = False
print(receiver)

dests, in_receivers, current, headers_indent = [], False, None, None
for ln in lines:
    if re.match(r"^receivers:\s*$", ln):
        in_receivers = True
        continue
    if not in_receivers:
        continue
    if ln and not ln.startswith(" ") and not ln.startswith("- "):
        break
    m = re.match(r"^- name:\s*(\S.*)$", ln)
    if m:
        current = m.group(1).strip().strip(QUOTES)
        headers_indent = None
        continue
    if current != receiver:
        continue
    indent = len(ln) - len(ln.lstrip(" "))
    # Skip the rendered SMTP headers block: it carries a canonical-cased "To:"
    # whose value is the same address, and counting it would double every
    # destination. Tracked by indentation rather than by case alone.
    if headers_indent is not None:
        if ln.strip() and indent > headers_indent:
            continue
        headers_indent = None
    if re.match(r"^\s*headers:\s*$", ln):
        headers_indent = indent
        continue
    m = re.match(r"^\s*-?\s*to:\s*(\S.*)$", ln)
    if m:
        dests.append(m.group(1).strip().strip(QUOTES))
for d in dests:
    print(d)
' "$1"
}

L3B_INFO=$(route_sink_block "$AM_CONFIG") \
  || void "L-3b could not parse the running Alertmanager config — an unparseable config is VOID, never clean"
L3B_RECEIVER=$(head -n1 <<<"$L3B_INFO")
L3B_DESTS=$(tail -n +2 <<<"$L3B_INFO" | sed '/^$/d')
# `command grep` throughout, matching the L-1b call above: in an interactive
# harness `grep` can be a shell function routed at ugrep, and the whole point of
# a counted result is that it must not silently change meaning.
L3B_N=$(command grep -c . <<<"$L3B_DESTS" || true)
[ -n "$L3B_DESTS" ] || L3B_N=0

[ -n "$L3B_RECEIVER" ] \
  || void "L-3b the running config declares no default route receiver — L-3 cannot claim a probe traversed the route real alerts take"
[ "${L3B_N:-0}" -gt 0 ] \
  || void "L-3b receiver '$L3B_RECEIVER' has ZERO email destinations, so a delivered probe would prove nothing about anyone's inbox"

# A route or receiver built specially for the probe defeats the whole assertion.
case "$AM_CONFIG" in
  *"$PROBE_BASE"*)
    void "L-3b the running Alertmanager config MENTIONS '$PROBE_BASE'. The probe must traverse the route real alerts take; a route or receiver of its own would make L-3 green while proving nothing about real delivery." ;;
esac

if [ "${L3B_N:-0}" -eq 1 ]; then
  L3B_SOLE=$(printf '%s' "$L3B_DESTS")
  for want in "$L3_SINK_TO" "$L3_HUMAN_TO"; do
    [ -z "$want" ] && continue
    [ "$want" = "$L3B_SOLE" ] \
      || void "L-3b declared address '$want' is not the destination of receiver '$L3B_RECEIVER' (which is '$L3B_SOLE') — the gate would be searching a sink the route does not feed"
  done
  L3B_NOTE="single destination ($L3B_SOLE) — the searched sink IS the real destination"
else
  { [ -n "$L3_SINK_TO" ] && [ -n "$L3_HUMAN_TO" ]; } \
    || void "L-3b receiver '$L3B_RECEIVER' has $L3B_N email destinations:
$(sed 's/^/         /' <<<"$L3B_DESTS")
       With more than one, this gate cannot know which address it can SEARCH and which a human READS.
       Declare both — L3_SINK_TO=<inspectable sink> L3_HUMAN_TO=<real inbox> — rather than letting the
       gate pick. Guessing here is how a green L-3 comes to mean the wrong leg was delivered."
  for want in "$L3_SINK_TO" "$L3_HUMAN_TO"; do
    command grep -Fxq -- "$want" <<<"$L3B_DESTS" \
      || void "L-3b declared address '$want' is NOT a destination of receiver '$L3B_RECEIVER':
$(sed 's/^/         /' <<<"$L3B_DESTS")
       Same receiver, same route, same template is the whole basis for reading the sink as evidence
       about the inbox. A destination in a DIFFERENT receiver is a side channel."
  done
  L3B_NOTE="$L3B_N destinations in receiver '$L3B_RECEIVER'; searched sink '$L3_SINK_TO' is co-resident with human inbox '$L3_HUMAN_TO'"
fi

# WHY THE ALERTNAME IS UNIQUE PER RUN (issue #342 item 6)
#
#   It was the constant "SyntheticDeliveryProbe" until 2026-07-29, and that made
#   this assertion report a delivery failure on every re-run inside five minutes.
#   Filed as "confirmed flaky". It is not flaky — it is deterministic, and the
#   mechanism is Alertmanager's dispatcher, not the transport:
#
#     route.group_by is ['alertname', 'service'] and the probe posts a constant
#     alertname with service=platform, so EVERY run lands in the SAME aggregation
#     group. A group notifies at group_wait (30s) when it is created, and after
#     that only on a group_interval (5m) tick. The per-run probe_id label does not
#     help: it is not in group_by, so it does not open a new group.
#
#   Measured on this stack, two runs back to back (scratch harness, both arms in
#   one execution so the stack state is identical):
#
#     CONSTANT alertname   run 1 delivered=1 after 30s | run 2 delivered=0 after 120s
#     UNIQUE   alertname   run 1 delivered=1 after 30s | run 2 delivered=1 after 30s
#
#   `notifications_failed_total{email}` was 0 -> 0 in all four, which is the whole
#   reason the diagnostic above had to become two counters.
#
#   This does NOT weaken the assertion. The probe still traverses the default
#   route, the same receiver, the same template and the same SMTP hop that real
#   alerts use; only the grouping key differs, and grouping is not what L-3
#   claims to prove. Fixing it in the Alertmanager config instead — a probe-only
#   route with group_wait 0s — was rejected for the opposite reason: it would make
#   the probe bypass the route real alerts take, proving less while looking like
#   it proved more.
#
# The SEARCH still keys on the per-run PROBE_ID, not on the alertname — and would
# still have to even now the alertname is unique. Searching for a constant name
# matches probe emails from EVERY PREVIOUS RUN still sitting in the sink, so once
# any probe has ever been delivered L-3 would pass forever regardless of whether
# this one arrived. Measured: with SMTP pointed at an unroutable host (192.0.2.1)
# the name-based search still reported delivered=1. A permanently-green transport
# check is exactly the defect class this gate exists to catch, and it was in the
# gate.
#
# The id goes in the ANNOTATIONS, not only in a label: the email template renders
# .Annotations.summary / .Annotations.description but not arbitrary labels, so a
# label-only id would never appear in the delivered message and could not be
# searched for.
curl -sf --max-time 10 -X POST "$ALERTMANAGER_URL/api/v2/alerts" -H 'Content-Type: application/json' \
  -d "[{\"labels\":{\"alertname\":\"$PROBE\",\"severity\":\"info\",\"service\":\"platform\",\"probe_id\":\"$PROBE_ID\"},\"annotations\":{\"summary\":\"synthetic transport probe $PROBE_ID — not a real alert\",\"description\":\"posted by scripts/check-alert-liveness.sh run $PROBE_ID\"}}]" \
  >/dev/null || void "cannot POST the synthetic alert to $ALERTMANAGER_URL"

FOUND=0
for _ in $(seq 1 "$L3_TRIES"); do
  hits=$(curl -sfG --max-time 10 "$MAILHOG_URL/api/v2/search" --data-urlencode "kind=containing" --data-urlencode "query=$PROBE_ID" \
         | jq -r '.total // 0')
  [ "${hits:-0}" -gt 0 ] && { FOUND=1; break; }
  sleep "$L3_SLEEP"
done

SENT_AFTER=$(am_counter alertmanager_notifications_total) \
  || void "cannot read $ALERTMANAGER_URL/metrics"
FAILED_AFTER=$(am_counter alertmanager_notifications_failed_total) \
  || void "cannot read $ALERTMANAGER_URL/metrics"

# Three outcomes, deliberately distinguished. Collapsing the first two into one
# message is what cost two days on #342 item 6.
if [ "$FOUND" -ne 1 ] && [ "$SENT_AFTER" = "$SENT_BEFORE" ]; then
  violation "L-3 Alertmanager never ATTEMPTED a notification for '$PROBE' within ${L3_TIMEOUT}s — notifications_total{email} did not move ($SENT_BEFORE -> $SENT_AFTER). This is a DISPATCH fault (routing, grouping, an active silence, an inhibit rule), NOT a transport one: the SMTP hop was never exercised, so a flat failed_total here means nothing. Inspect: curl -s $ALERTMANAGER_URL/api/v2/alerts/groups | jq · curl -s $ALERTMANAGER_URL/api/v2/silences | jq"
elif [ "$FOUND" -ne 1 ]; then
  violation "L-3 Alertmanager ATTEMPTED delivery (notifications_total{email} $SENT_BEFORE -> $SENT_AFTER) but probe '$PROBE_ID' never arrived at the destination within ${L3_TIMEOUT}s (failed_total{email} $FAILED_BEFORE -> $FAILED_AFTER). The transport WAS exercised and the message did not land."
elif [ "$FAILED_AFTER" != "$FAILED_BEFORE" ]; then
  violation "L-3 delivery reported but alertmanager_notifications_failed_total{email} increased $FAILED_BEFORE -> $FAILED_AFTER"
fi

# expire the probe so it does not linger in the UI
curl -sf --max-time 10 -X POST "$ALERTMANAGER_URL/api/v2/alerts" -H 'Content-Type: application/json' \
  -d "[{\"labels\":{\"alertname\":\"$PROBE\",\"severity\":\"info\",\"service\":\"platform\",\"probe_id\":\"$PROBE_ID\"},\"endsAt\":\"$(date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%S.000Z)\"}]" \
  >/dev/null 2>&1 || true

# ================================================================== summary
echo "alert-liveness summary  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  L-1   targets=$TARGET_N  down=$L1_DOWN"
echo "  L-1b  exporter-jobs=${#EXPORTER_GAUGES[@]}  blind=$L1B_BLIND  gauge-read-by-no-rule=$L1B_UNREAD"
echo "  L-2   rules=$RULE_N  selectors-matching-0-series=$L2_EMPTY"
echo "  L-2b  wrong-subject=$L2B_WRONG"
echo "  L-3   probe delivered=$FOUND  attempts{email} $SENT_BEFORE -> $SENT_AFTER  failed{email} $FAILED_BEFORE -> $FAILED_AFTER"
echo "  L-3b  $L3B_NOTE"
echo "  NOTE  L-3 proves the message left Alertmanager and arrived at the configured sink."
if [ -n "$L3_HUMAN_TO" ]; then
  echo "        L-3b proves that sink shares a receiver with $L3_HUMAN_TO, and the recorded A1 measurement"
  echo "        proves Alertmanager fans out to every entry in a receiver — so the human's leg was sent too."
  echo "        It still does NOT prove a human READ it."
else
  echo "        It does NOT prove a human reads that sink. In compose that sink is Mailhog with no human behind it."
fi

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "FAILED: $VIOLATIONS live detection defect(s)." >&2
  exit 1
fi
echo "PASS: $TARGET_N targets up, ${#EXPORTER_GAUGES[@]} exporter gauge(s) healthy and rule-referenced, $RULE_N rules match live series of the job they claim, transport verified end-to-end."
