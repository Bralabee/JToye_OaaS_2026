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
#         at the configured destination, AND no email integration on its route is
#         counted failed through Alertmanager's retry horizon (route.group_interval
#         + 30 s, read from the running Alertmanager; plan 29-25). A healthy run
#         therefore takes about group_interval longer (~5.5 min at 5m).
#
#   WHAT L-3 PROVES AND DOES NOT PROVE: it proves the message left Alertmanager
#   and arrived at the configured sink; it does NOT prove a human reads that
#   sink, and today that sink is Mailhog at ops@jtoye.local with no human behind
#   it. A real ServiceDown fired here for over 46 hours and reached nobody.
#   Do not report a green L-3 as "operators are now notified".
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
#   by default, kubectl in Kubernetes) or exec target, unreachable Prometheus or
#   Alertmanager, ZERO targets or ZERO rules discovered, an unparseable
#   expression, an exporter job with no gauge mapping, an unmapped `service:`
#   label, or a destination that cannot be inspected. "Found nothing" is NEVER
#   "clean", and an unmapped new thing must stop the gate rather than skip.
#   USAGE ERROR (exit 2, before any call): SINK other than mailhog or mailpit.
#
# RUNTIMES (plan 29-23)
#   compose (the default; every variable unset):
#     bash scripts/check-alert-liveness.sh
#   staging Kubernetes (port-forwards first: svc/prometheus 19090:9090, svc/mailpit 18025:8025,
#   and from plan 29-25 svc/alertmanager 19093:9093). ALERTMANAGER_URL is ALWAYS set explicitly:
#   its default, localhost:9093, can be the LOCAL compose Alertmanager on the operator host.
#     PROM_URL=http://localhost:19090 ALERTMANAGER_URL=http://localhost:19093 \
#     PROM_EXEC='kubectl --context jtoye-stg-aks -n jtoye-staging exec deploy/prometheus --' \
#     SINK=mailpit MAILPIT_URL=http://localhost:18025 bash scripts/check-alert-liveness.sh
#   bash scripts/check-alert-liveness.sh --selftest   (stubs only; no stack, no network)
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# ================================================================== --selftest
# The runtime abstractions (plan 29-23) shown working against STUBS: stub curl answers as
# Prometheus (prom.stub), Alertmanager (am.stub), Mailhog (mailhog.stub) and Mailpit
# (mailpit.stub); stub docker and kubectl answer md5sum/stat/inspect. Every stub appends its argv
# to one log, so an arm asserts WHICH command the gate ran, not only its exit code. Each arm runs
# this script for real ("$BASH" "$SELF") with the stubs first on PATH; jq, python3 and md5sum stay
# real. LIVENESS_SELFTEST_FORMAT=tap prints TAP 13 for the RED-evidence classifier.
#
#   compose-default-docker-exec  PROM_EXEC unset: L-0 is `docker exec jtoye-prometheus md5sum
#                                /etc/prometheus/alerts.yml`, kubectl never runs, rc 0
#   k8s-prom-exec-kubectl        PROM_EXEC=kubectl ... exec deploy/prometheus --: L-0 runs md5sum
#                                through it, docker never runs, no copy command anywhere, rc 0
#   mailhog-sink-calls           SINK=mailhog: the Mailhog /api/v2 messages and search calls, rc 0
#   mailpit-unreachable-void     SINK=mailpit, Mailpit down: rc 2 and a VOID naming Mailpit, never
#                                a PASS line
#   mailpit-sink-found           SINK=mailpit, the probe found: /api/v1/messages and /api/v1/search
#                                called, the search asks for the probe id, rc 0
#   mailpit-counts-matches-only  SINK=mailpit, mailbox total 10 but messages_count 0: NOT found,
#                                rc 1 (reading the mailbox total would be a permanent pass)
#   unknown-sink-usage           SINK=bogus: rc 2 and a USAGE ERROR line, before any call
#   l0-drift-void                the served md5 differs: rc 2, L-0 SOURCE DRIFT
#   late-integration-failure     the probe reaches the sink, then failed_total{email} rises inside
#                                Alertmanager's retry window: rc 1, a FAIL naming the late failure
#                                (plan 29-25: a broken Zoho credential PASSED the 120 s L-3)
#   am-status-unreadable-void    /api/v2/status unreadable: rc 2, a VOID naming group_interval
liveness_selftest() {
  local fmt="${LIVENESS_SELFTEST_FORMAT:-plain}" st n=0 bad=0 tap=() line
  st="$(mktemp -d)" || { echo "VOID: --selftest could not create a temp dir" >&2; return 2; }
  # shellcheck disable=SC2064
  trap "rm -rf '$st'" EXIT
  mkdir -p "$st/bin"
  for t in jq python3 md5sum; do
    command -v "$t" >/dev/null 2>&1 || { echo "VOID: --selftest needs $t on PATH" >&2; return 2; }
  done

  cat > "$st/bin/curl" <<'STUB'
#!/usr/bin/env bash
url="" method=GET data=()
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[$i]}" in
    http://*|https://*) url="${args[$i]}" ;;
    --data-urlencode) i=$((i + 1)); data+=("${args[$i]}") ;;
    -X) i=$((i + 1)); method="${args[$i]}" ;;
  esac
done
echo "curl $method $url ${data[*]}" >> "$STUB_LOG"
rest="${url#*://}"; host="${rest%%/*}"; path="/${rest#*/}"; path="${path%%\?*}"
case "$host" in
  prom.stub) case "$path" in
      /-/healthy) echo ok ;;
      /api/v1/targets) echo '{"data":{"activeTargets":[{"labels":{"job":"prometheus"},"health":"up","scrapeUrl":"http://p/metrics","lastError":""}]}}' ;;
      /api/v1/query) echo '{"status":"success","data":{"result":[{"value":[0,"1"]}]}}' ;;
      /api/v1/rules)
        # STUB_RULE_CERT=1 adds the k8s-only rule (issue #972) to the rules the stub serves.
        if [ -n "${STUB_RULE_CERT:-}" ]; then
          echo '{"data":{"groups":[{"rules":[{"type":"alerting","name":"ServiceDown","labels":{"service":"platform"},"query":"up == 0"},{"type":"alerting","name":"CertificateExpiringSoon","labels":{"service":"platform"},"query":"certmanager_certificate_expiration_timestamp_seconds - time() < 7 * 86400"}]}]}}'
        else
          echo '{"data":{"groups":[{"rules":[{"type":"alerting","name":"ServiceDown","labels":{"service":"platform"},"query":"up == 0"}]}]}}'
        fi ;;
      /api/v1/series)
        # certmanager_* series exist only when STUB_CERT_SERIES is set (the cluster has them, compose does not)
        case "${data[*]}" in
          *certmanager_*) if [ -n "${STUB_CERT_SERIES:-}" ]; then echo '{"data":[{"__name__":"certmanager_certificate_expiration_timestamp_seconds","job":"cert-manager"}]}'; else echo '{"data":[]}'; fi ;;
          *) echo '{"data":[{"__name__":"up","job":"prometheus"}]}' ;;
        esac ;;
      *) exit 22 ;;
    esac ;;
  am.stub) case "$path" in
      /-/healthy) echo ok ;;
      /metrics)
        # One counter per run: a failure that Alertmanager reports only when an integration's
        # retry window closes (plan 29-25) is a failed_total of 1 from the STUB_FAIL_FROM_CALL-th read.
        c=$(( $(cat "$STUB_LOG.amcalls" 2>/dev/null || echo 0) + 1 )); echo "$c" > "$STUB_LOG.amcalls"
        f=0; [ -n "${STUB_FAIL_FROM_CALL:-}" ] && [ "$c" -ge "$STUB_FAIL_FROM_CALL" ] && f=1
        printf '%s\n' 'alertmanager_notifications_total{integration="email"} 1' "alertmanager_notifications_failed_total{integration=\"email\"} $f" ;;
      /api/v2/status) [ "${STUB_AM_STATUS:-up}" = down ] && exit 22
        printf '{"config":{"original":"route:\\n  receiver: r\\n  group_wait: 0s\\n  group_interval: %s\\nreceivers:\\n- name: r\\n"}}\n' "${STUB_GI:-0s}" ;;
      /api/v2/alerts) echo '{}' ;;
      *) exit 22 ;;
    esac ;;
  mailhog.stub) [ "${STUB_MAILHOG:-up}" = down ] && exit 7
    case "$path" in
      /api/v2/messages) echo '{"total":1,"items":[]}' ;;
      /api/v2/search) echo "{\"total\":${STUB_FOUND:-1}}" ;;
      *) exit 22 ;;
    esac ;;
  mailpit.stub) [ "${STUB_MAILPIT:-up}" = down ] && exit 7
    case "$path" in
      /api/v1/messages) echo '{"total":10,"messages_count":10,"messages":[]}' ;;
      /api/v1/search) echo "{\"total\":10,\"messages_count\":${STUB_FOUND:-1},\"messages\":[]}" ;;
      *) exit 22 ;;
    esac ;;
  *) exit 6 ;;
esac
STUB
  cat > "$st/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "${0##*/} $*" >> "$STUB_LOG"
prev=""
for a in "$@"; do
  case "$prev" in
    md5sum) echo "${STUB_SUM}  $a"; exit 0 ;;
    -c) echo 4242; exit 0 ;;
  esac
  prev="$a"
done
case " $* " in *" inspect "*|*" get "*) exit 0 ;; esac
exit 0
STUB
  cp "$st/bin/docker" "$st/bin/kubectl"
  chmod +x "$st/bin/curl" "$st/bin/docker" "$st/bin/kubectl"

  local fixture="$st/alerts.yml" sum
  printf 'groups:\n  - name: g\n    rules:\n      - alert: ServiceDown\n        expr: up == 0\n' > "$fixture"
  sum=$(md5sum "$fixture" | awk '{print $1}')

  # arm <name> <want-rc> <check-shell> [VAR=value ...]: runs the gate with the stubs, then
  # evaluates <check-shell> with $out (combined output) and $log (the stub argv log) set.
  arm() {
    local name="$1" want="$2" check="$3" out rc log why=""; shift 3
    n=$((n + 1))
    log="$st/$name.log"; : > "$log"
    out=$(env PATH="$st/bin:$PATH" STUB_LOG="$log" STUB_SUM="$sum" \
          ALERTS="$fixture" PROM_URL=http://prom.stub ALERTMANAGER_URL=http://am.stub \
          MAILHOG_URL=http://mailhog.stub MAILPIT_URL=http://mailpit.stub \
          LIVENESS_L3_TRIES=2 LIVENESS_L3_SLEEP=0 LIVENESS_L3_SETTLE_MARGIN=0 "$@" "$BASH" "$SELF" 2>&1); rc=$?
    [ "$rc" -eq "$want" ] || why="want rc $want, got rc $rc"
    if [ -z "$why" ] && ! eval "$check"; then why="check failed: $check"; fi
    if [ -z "$why" ]; then
      line="ARM $name ok"; [ "$fmt" = tap ] && tap+=("ok $n - $name")
    else
      bad=$((bad + 1))
      line="ARM $name FAIL ($why) | log: $(tr '\n' ';' < "$log" | cut -c1-300) | out: $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-300)"
      [ "$fmt" = tap ] && tap+=("not ok $n - $name" "  ---" "  message: '${line//\'/}'" "  ...")
    fi
    [ "$fmt" = tap ] || echo "$line"
  }

  arm compose-default-docker-exec 0 \
    'command grep -qxF "docker exec jtoye-prometheus md5sum /etc/prometheus/alerts.yml" "$log" && ! command grep -q "^kubectl " "$log"'
  arm k8s-prom-exec-kubectl 0 \
    'command grep -qxF "kubectl --context jtoye-stg-aks -n jtoye-staging exec deploy/prometheus -- md5sum /etc/prometheus/alerts.yml" "$log" && ! command grep -q "^docker " "$log" && ! command grep -qE "(^| )cp( |$)" "$log" && command grep -q "^L-0 OK: served md5=$sum == .* (via kubectl)$" <<<"$out"' \
    PROM_EXEC='kubectl --context jtoye-stg-aks -n jtoye-staging exec deploy/prometheus --'
  # Issue #972: a rule whose series only the cluster produces. Against compose (PROM_EXEC docker)
  # its empty selector is skipped out loud; against the cluster (PROM_EXEC kubectl) it is judged
  # like any other rule and an empty selector is a violation; with the series present it passes.
  arm k8s-only-rule-skipped-on-compose 0 \
    'command grep -q "^L-2   .CertificateExpiringSoon. skipped: K8S-ONLY rule" <<<"$out" && ! command grep -q "certmanager_" "$log"' \
    STUB_RULE_CERT=1
  arm k8s-only-rule-enforced-on-k8s 1 \
    'command grep -q "^FAIL: L-2 rule .CertificateExpiringSoon. selector .certmanager_certificate_expiration_timestamp_seconds. matches 0 series" <<<"$out"' \
    STUB_RULE_CERT=1 PROM_EXEC='kubectl --context jtoye-stg-aks -n jtoye-staging exec deploy/prometheus --'
  arm k8s-only-rule-live-on-k8s 0 \
    'command grep -q "^curl GET http://prom.stub/api/v1/series .*certmanager_" "$log" && ! command grep -q "skipped: K8S-ONLY" <<<"$out"' \
    STUB_RULE_CERT=1 STUB_CERT_SERIES=1 PROM_EXEC='kubectl --context jtoye-stg-aks -n jtoye-staging exec deploy/prometheus --'
  arm mailhog-sink-calls 0 \
    'command grep -q "^curl GET http://mailhog.stub/api/v2/messages" "$log" && command grep -q "^curl GET http://mailhog.stub/api/v2/search" "$log" && ! command grep -q "mailpit.stub" "$log"' \
    SINK=mailhog
  arm mailpit-unreachable-void 2 \
    'command grep -q "^VOID: .*Mailpit" <<<"$out" && ! command grep -q "^PASS" <<<"$out"' \
    SINK=mailpit STUB_MAILPIT=down
  arm mailpit-sink-found 0 \
    'command grep -q "^curl GET http://mailpit.stub/api/v1/messages" "$log" && command grep -q "^curl GET http://mailpit.stub/api/v1/search .*probe-" "$log" && ! command grep -q "mailhog.stub" "$log"' \
    SINK=mailpit
  arm mailpit-counts-matches-only 1 \
    'command grep -q "^FAIL: L-3" <<<"$out" && command grep -q "^curl GET http://mailpit.stub/api/v1/search" "$log" && ! command grep -q "mailhog.stub" "$log"' \
    SINK=mailpit STUB_FOUND=0
  # plan 29-25: one receiver, two integrations. The sink copy arrives at once, while the
  # human integration (Zoho) fails only when its retry window (route.group_interval) closes:
  # measured live, 535 at 17:45:45Z, failed_total 0 -> 1 at 17:50:45Z, and the 120 s L-3 window
  # had long ended with a PASS. L-3 must hold until that window has passed.
  arm late-integration-failure 1 \
    'command grep -q "^FAIL: L-3 .*after the probe reached" <<<"$out" && command grep -q "^curl GET http://am.stub/api/v2/status" "$log" && ! command grep -q "^PASS" <<<"$out"' \
    SINK=mailpit STUB_FAIL_FROM_CALL=5
  arm am-status-unreadable-void 2 \
    'command grep -q "^VOID: .*group_interval" <<<"$out" && ! command grep -q "^PASS" <<<"$out"' \
    SINK=mailpit STUB_AM_STATUS=down
  arm unknown-sink-usage 2 \
    'command grep -q "^USAGE ERROR" <<<"$out" && ! [ -s "$log" ]' \
    SINK=bogus
  arm l0-drift-void 2 \
    'command grep -q "L-0 SOURCE DRIFT" <<<"$out"' \
    STUB_SUM=00000000000000000000000000000000

  if [ "$fmt" = tap ]; then
    printf '%s\n' "TAP version 13" "1..$n" "${tap[@]}"
  else
    echo "selftest: $((n - bad))/$n arms as expected"
  fi
  [ "$bad" -eq 0 ]
}
SELF="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"
if [ "${1:-}" = "--selftest" ]; then
  set +e
  liveness_selftest; exit $?
fi

PROM_URL="${PROM_URL:-http://localhost:9091}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"
ALERTS="${ALERTS:-infra/monitoring/prometheus/alerts.yml}"
# L-0 needs to read the file out of the running Prometheus. Both are overridable
# because neither is a property of this script — a k8s Prometheus has a different
# container name and a different in-container path.
PROM_CONTAINER="${PROM_CONTAINER:-jtoye-prometheus}"
PROM_ALERTS_PATH="${PROM_ALERTS_PATH:-/etc/prometheus/alerts.yml}"
# The command L-0 reads the served file through (plan 29-23, Blocker B). The default is the
# compose form, byte-for-byte what this gate always ran; in Kubernetes:
#   PROM_EXEC='kubectl --context jtoye-stg-aks -n jtoye-staging exec deploy/prometheus --'
# See the L-0 block for why it is an EXEC and never a copy, in either runtime.
PROM_EXEC="${PROM_EXEC:-docker exec $PROM_CONTAINER}"
# The L-3 sink this gate can SEARCH (plan 29-23): mailhog (compose, the /api/v2 API at
# MAILHOG_URL) or mailpit (staging's in-cluster Mailpit, the /api/v1 API at MAILPIT_URL, reached
# through `kubectl port-forward svc/mailpit 18025:8025`). Anything else is a USAGE ERROR, so a
# typo can never fall back to searching a sink the route does not feed.
SINK="${SINK:-mailhog}"
MAILPIT_URL="${MAILPIT_URL:-http://localhost:18025}"
# The L-3 wait: 40 polls 3 s apart. Overridable only so --selftest's not-found arm is not a
# two-minute wait; the default is what every real run uses.
L3_TRIES="${LIVENESS_L3_TRIES:-40}"
L3_SLEEP="${LIVENESS_L3_SLEEP:-3}"

void() { echo "VOID: $*" >&2; exit 2; }
VIOLATIONS=0
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }

case "$SINK" in
  mailhog|mailpit) ;;
  *) echo "USAGE ERROR: SINK must be 'mailhog' or 'mailpit' (got '$SINK'); nothing was checked" >&2; exit 2 ;;
esac
case "$L3_TRIES$L3_SLEEP" in
  *[!0-9]*|'') echo "USAGE ERROR: LIVENESS_L3_TRIES and LIVENESS_L3_SLEEP must be non-negative integers" >&2; exit 2 ;;
esac
[ "$L3_TRIES" -ge 1 ] || { echo "USAGE ERROR: LIVENESS_L3_TRIES must be at least 1" >&2; exit 2; }

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
# `cert-manager` (issue #972) is DIRECT too: cert-manager's own /metrics, a k8s-only job (compose has
# no cert-manager, so it is only ever among the discovered jobs on the staging Prometheus).
DIRECT_JOBS=("prometheus" "core-java" "edge-go" "keycloak" "rabbitmq" "rabbitmq-queues" "cert-manager")

# ---- rules whose series exist only in the cluster (issue #972) ----------------
# scripts/alert-k8s-only.txt, read here only for the NAMES. Against compose an empty selector on
# one of these is expected (the runtime cannot produce it) and is skipped out loud; when PROM_EXEC
# drives kubectl the gate is looking at the cluster and no exemption applies. Hygiene (a live
# name, a reason, an owner) is check-alert-metrics.sh's.
K8S_ONLY_FILE="${K8S_ONLY_FILE:-$SCRIPT_DIR/alert-k8s-only.txt}"
[ -r "$K8S_ONLY_FILE" ] || void "k8s-only list not readable: $K8S_ONLY_FILE"
K8S_ONLY_RULES=" $(awk -F'|' '!/^[[:space:]]*#/ && NF { printf "%s ", $1 }' "$K8S_ONLY_FILE")"
case "$PROM_EXEC" in *kubectl*) ALERT_RUNTIME=k8s ;; *) ALERT_RUNTIME=compose ;; esac
K8S_ONLY_SKIPPED=0

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
# THE SAME ASSERTION IN KUBERNETES (plan 29-23, Blocker B). Every read below goes through
# PROM_EXEC, which defaults to `docker exec $PROM_CONTAINER`, so compose behaves exactly as
# before. Against the staging Prometheus it is
#   PROM_EXEC='kubectl --context jtoye-stg-aks -n jtoye-staging exec deploy/prometheus --'
# and the in-pod path is the same /etc/prometheus/alerts.yml (k8s/components/monitoring mounts
# the config and the rules side by side). What L-0 catches there is different and just as real: a
# ConfigMap is a directory of symlinks the kubelet re-points atomically, so the inode-detach above
# cannot happen, but a pod can serve an OLDER ConfigMap version than the one applied (the kubelet
# syncs on its own period, and this Prometheus has no /-/reload). A byte-exact md5 of what the
# process reads catches both.
#
# NEVER `kubectl cp` EITHER. It is the Kubernetes analogue of the docker cp blindness measured
# above: it reads through the API with tar, which is not what the process has open, and it is
# exactly the comparison this assertion must not make. Exec md5sum inside the container.
read -r -a PROM_EXEC_ARGV <<<"$PROM_EXEC"
[ "${#PROM_EXEC_ARGV[@]}" -gt 0 ] \
  || void "L-0 PROM_EXEC is empty — there is no command with which to read the served alerts file"
command -v "${PROM_EXEC_ARGV[0]}" >/dev/null 2>&1 \
  || void "L-0 '${PROM_EXEC_ARGV[0]}' (the first word of PROM_EXEC) is not on PATH, so the running Prometheus's own copy of $ALERTS cannot be read. Unverifiable parity is VOID, never clean — set PROM_EXEC/PROM_ALERTS_PATH for this runtime."
# Existence first, by NAME, so a missing container is reported as missing and never as drift.
# The compose default keeps its `docker inspect`; an explicit PROM_EXEC is its own probe (the
# md5sum exec below VOIDs when the target is absent).
if [ "$PROM_EXEC" = "docker exec $PROM_CONTAINER" ]; then
  docker inspect "$PROM_CONTAINER" >/dev/null 2>&1 \
    || void "L-0 container '$PROM_CONTAINER' not found — cannot compare the served alerts file against $ALERTS (override with PROM_CONTAINER, or PROM_EXEC for another runtime)"
fi

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
       KUBERNETES: the pod serves an older ConfigMap version than the one applied; redeploy the
       current render (the hashed ConfigMap name rolls the pod), or restart it:
         kubectl --context <ctx> -n <ns> rollout restart deploy/prometheus"
fi
L0_NOTE="served md5=$CTR_SUM == $ALERTS md5=$HOST_SUM (via ${PROM_EXEC_ARGV[0]})"
# Printed NOW, not only in the summary: a run that VOIDs later (an absent Alertmanager at L-3)
# must still show what L-0 compared, or the parity it established is unrecorded.
echo "L-0 OK: $L0_NOTE"

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

  if [ "$ALERT_RUNTIME" != k8s ] && [[ "$K8S_ONLY_RULES" == *" $name "* ]]; then
    K8S_ONLY_SKIPPED=$((K8S_ONLY_SKIPPED + 1))
    echo "L-2   '$name' skipped: K8S-ONLY rule (scripts/alert-k8s-only.txt) and PROM_EXEC is not kubectl (runtime=$ALERT_RUNTIME)"
    continue
  fi

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

# THE SINK ADAPTERS (plan 29-23). Two read-only operations per sink: inspect (is the sink there
# and answering its API) and hits (how many messages match the probe id).
#   mailhog  GET /api/v2/messages?limit=1 ; GET /api/v2/search?kind=containing&query=<id> -> .total
#   mailpit  GET /api/v1/messages?limit=1 ; GET /api/v1/search?query="<id>" -> .messages_count
# Mailpit's paths were read off the running v1.31.6 instance's /api/v1/swagger.json (2026-10-19).
# In ITS search response `total` is the number of messages in the MAILBOX and messages_count the
# number MATCHING the query; reading `total` would find the probe in any non-empty mailbox, a
# permanently green L-3 (the --selftest arm mailpit-counts-matches-only pins it). The id is
# searched as a quoted phrase, so Mailpit's search syntax cannot read its hyphens as operators.
# Both are read-only: Mailpit's send API stays locked (plan 29-22) and this gate never posts to it.
sink_name() { case "$SINK" in mailhog) echo "Mailhog at $MAILHOG_URL" ;; mailpit) echo "Mailpit at $MAILPIT_URL" ;; esac; }
sink_inspect() {
  case "$SINK" in
    mailhog) curl -sf --max-time 10 "$MAILHOG_URL/api/v2/messages?limit=1" >/dev/null 2>&1 ;;
    mailpit) curl -sf --max-time 10 "$MAILPIT_URL/api/v1/messages?limit=1" >/dev/null 2>&1 ;;
  esac
}
sink_hits() { # sink_hits <probe id> -> number of matching messages (empty on a failed call)
  case "$SINK" in
    mailhog) curl -sfG --max-time 10 "$MAILHOG_URL/api/v2/search" --data-urlencode "kind=containing" --data-urlencode "query=$1" \
               | jq -r '.total // 0' ;;
    mailpit) curl -sfG --max-time 10 "$MAILPIT_URL/api/v1/search" --data-urlencode "query=\"$1\"" \
               | jq -r '.messages_count // 0' ;;
  esac
}
sink_inspect \
  || void "destination ($(sink_name)) is not inspectable — an unverifiable transport is VOID, never clean"

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

L3_TIMEOUT=$((L3_TRIES * L3_SLEEP))

# THE RETRY HORIZON (plan 29-25). One receiver can hold several email integrations (staging:
# Zoho for the human, Mailpit as the inspectable copy). The sink copy lands at once, but a
# FAILING integration is retried and counted in failed_total only when its retry context ends,
# which Alertmanager sets to the group's group_interval (dispatch.go). Measured on staging with a
# wrong Zoho password: 535 at 17:45:45Z, Mailpit copy at 17:45:45Z, failed_total 0 -> 1 at
# 17:50:45Z ("notify retry canceled after 16 attempts"). L-3 used to stop at the first sink hit,
# so that broken human path PASSED. L-3 therefore holds, after the hit, until group_interval plus
# a margin has passed, polling failed_total; the value is read from the RUNNING Alertmanager
# (/api/v2/status, its normalised config always carries route.group_interval), never assumed.
# The margin is overridable only so --selftest need not wait; there is no knob that skips it.
duration_s() { # duration_s <prometheus duration, e.g. 5m, 1m30s, 500ms> -> whole seconds (ceil)
  awk -v d="$1" 'BEGIN {
    if (d !~ /^([0-9]+(ms|s|m|h|d|w|y))+$/) exit 1
    t = 0; ms = 0
    while (match(d, /^[0-9]+(ms|s|m|h|d|w|y)/)) {
      tok = substr(d, 1, RLENGTH); d = substr(d, RLENGTH + 1)
      n = tok + 0; u = tok; sub(/^[0-9]+/, "", u)
      if (u == "ms") ms += n; else if (u == "s") t += n; else if (u == "m") t += 60 * n
      else if (u == "h") t += 3600 * n; else if (u == "d") t += 86400 * n
      else if (u == "w") t += 604800 * n; else t += 31536000 * n
    }
    printf "%d\n", t + int((ms + 999) / 1000)
  }'
}
AM_STATUS=$(curl -sf --max-time 10 "$ALERTMANAGER_URL/api/v2/status" | jq -r '.config.original // empty') || AM_STATUS=""
AM_GROUP_INTERVAL=$(awk '/^route:/ {r = 1; next} r && /^[^ ]/ {exit} r && /^  group_interval: / {print $2; exit}' <<<"$AM_STATUS")
[ -n "$AM_GROUP_INTERVAL" ] \
  || void "cannot read route.group_interval from $ALERTMANAGER_URL/api/v2/status — without it L-3 cannot know how long a failing integration takes to be counted"
L3_SETTLE_GI=$(duration_s "$AM_GROUP_INTERVAL") \
  || void "route.group_interval '$AM_GROUP_INTERVAL' from $ALERTMANAGER_URL/api/v2/status is not a duration"
L3_SETTLE_MARGIN="${LIVENESS_L3_SETTLE_MARGIN:-30}"
case "$L3_SETTLE_MARGIN" in *[!0-9]*|'') echo "USAGE ERROR: LIVENESS_L3_SETTLE_MARGIN must be a non-negative integer" >&2; exit 2 ;; esac
L3_SETTLE=$((L3_SETTLE_GI + L3_SETTLE_MARGIN))

PROBE_BASE="SyntheticDeliveryProbe"
RUN_TOKEN="$(jq -rn 'now|floor|tostring')-$$"
PROBE="${PROBE_BASE}-${RUN_TOKEN}"
PROBE_ID="probe-${RUN_TOKEN}"

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
  hits=$(sink_hits "$PROBE_ID" || true)
  [ "${hits:-0}" -gt 0 ] && { FOUND=1; break; }
  sleep "$L3_SLEEP"
done

SENT_AFTER=$(am_counter alertmanager_notifications_total) \
  || void "cannot read $ALERTMANAGER_URL/metrics"
FAILED_AFTER=$(am_counter alertmanager_notifications_failed_total) \
  || void "cannot read $ALERTMANAGER_URL/metrics"

# Hold through the retry horizon (see THE RETRY HORIZON above): only after a hit and a flat
# failed_total so far. At least one read, then until the deadline; a rise ends the wait.
FAILED_SETTLED="$FAILED_AFTER"
SETTLED_FOR=0
if [ "$FOUND" -eq 1 ] && [ "$FAILED_AFTER" = "$FAILED_BEFORE" ]; then
  settle_start=$(date +%s)
  settle_deadline=$((settle_start + L3_SETTLE))
  while :; do
    FAILED_SETTLED=$(am_counter alertmanager_notifications_failed_total) \
      || void "cannot read $ALERTMANAGER_URL/metrics while holding L-3 through the retry horizon"
    [ "$FAILED_SETTLED" != "$FAILED_BEFORE" ] && break
    [ "$(date +%s)" -ge "$settle_deadline" ] && break
    sleep "$((L3_SLEEP > 0 ? L3_SLEEP : 1))"
  done
  SETTLED_FOR=$(( $(date +%s) - settle_start ))
fi

# Three outcomes, deliberately distinguished. Collapsing the first two into one
# message is what cost two days on #342 item 6.
if [ "$FOUND" -ne 1 ] && [ "$SENT_AFTER" = "$SENT_BEFORE" ]; then
  violation "L-3 Alertmanager never ATTEMPTED a notification for '$PROBE' within ${L3_TIMEOUT}s — notifications_total{email} did not move ($SENT_BEFORE -> $SENT_AFTER). This is a DISPATCH fault (routing, grouping, an active silence, an inhibit rule), NOT a transport one: the SMTP hop was never exercised, so a flat failed_total here means nothing. Inspect: curl -s $ALERTMANAGER_URL/api/v2/alerts/groups | jq · curl -s $ALERTMANAGER_URL/api/v2/silences | jq"
elif [ "$FOUND" -ne 1 ]; then
  violation "L-3 Alertmanager ATTEMPTED delivery (notifications_total{email} $SENT_BEFORE -> $SENT_AFTER) but probe '$PROBE_ID' never arrived at the destination within ${L3_TIMEOUT}s (failed_total{email} $FAILED_BEFORE -> $FAILED_AFTER). The transport WAS exercised and the message did not land."
elif [ "$FAILED_AFTER" != "$FAILED_BEFORE" ]; then
  violation "L-3 delivery reported but alertmanager_notifications_failed_total{email} increased $FAILED_BEFORE -> $FAILED_AFTER"
elif [ "$FAILED_SETTLED" != "$FAILED_BEFORE" ]; then
  violation "L-3 failed_total{email} rose $FAILED_BEFORE -> $FAILED_SETTLED ${SETTLED_FOR}s after the probe reached $(sink_name): another email integration on the same route (the human one, when the sink is a copy) failed and was counted when its retry window closed (group_interval $AM_GROUP_INTERVAL). The sink copy does not prove the human path. Inspect: the Alertmanager log for 'Notify attempt failed'."
fi

# expire the probe so it does not linger in the UI
curl -sf --max-time 10 -X POST "$ALERTMANAGER_URL/api/v2/alerts" -H 'Content-Type: application/json' \
  -d "[{\"labels\":{\"alertname\":\"$PROBE\",\"severity\":\"info\",\"service\":\"platform\",\"probe_id\":\"$PROBE_ID\"},\"endsAt\":\"$(date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%S.000Z)\"}]" \
  >/dev/null 2>&1 || true

# ================================================================== summary
echo "alert-liveness summary  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  L-0   $L0_NOTE"
echo "  L-1   targets=$TARGET_N  down=$L1_DOWN"
echo "  L-1b  exporter-jobs=${#EXPORTER_GAUGES[@]}  blind=$L1B_BLIND  gauge-read-by-no-rule=$L1B_UNREAD"
echo "  L-2   rules=$RULE_N  selectors-matching-0-series=$L2_EMPTY"
echo "  L-2b  wrong-subject=$L2B_WRONG"
echo "  L-3   probe delivered=$FOUND  sink=$(sink_name)  attempts{email} $SENT_BEFORE -> $SENT_AFTER  failed{email} $FAILED_BEFORE -> $FAILED_AFTER -> $FAILED_SETTLED after ${SETTLED_FOR}s (retry horizon ${L3_SETTLE}s = group_interval $AM_GROUP_INTERVAL + ${L3_SETTLE_MARGIN}s)"
echo "  NOTE  L-3 proves the message left Alertmanager and arrived at the configured sink."
echo "        It does NOT prove a human reads that sink ($SINK has no human behind it)."

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "FAILED: $VIOLATIONS live detection defect(s)." >&2
  exit 1
fi
echo "PASS: $TARGET_N targets up, ${#EXPORTER_GAUGES[@]} exporter gauge(s) healthy and rule-referenced, $RULE_N rules match live series of the job they claim, transport verified end-to-end."
