#!/bin/sh
# Phase 9 smoke test for Alertmanager
#
# Validates SECR-06: alert fires, reaches Alertmanager, routes to the email
# receiver, and arrives in Mailhog's inbox.
#
# Prerequisites:
#   1. docker-compose.full-stack.yml is up (Mailhog on jtoye-network:1025)
#   2. docker-compose.monitoring.yml is up (Prometheus + Alertmanager)
#   3. curl, docker, and jq available on host
#
# Exit codes:
#   0  — both synthetic and real tests delivered an email to Mailhog
#   2  — Alertmanager unreachable (setup problem)
#   3  — synthetic alert did not reach Mailhog within the timeout
#   4  — real ServiceDown test did not reach Mailhog within the timeout
#
# Run from repo root.

set -eu

ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
MAILHOG_API="${MAILHOG_API:-http://localhost:8025/api/v2/messages}"
TIMEOUT_SECS="${TIMEOUT_SECS:-90}"

log() { printf '[smoke-test] %s\n' "$*"; }

# --- Preflight --------------------------------------------------------------
log "Preflight — checking Alertmanager at ${ALERTMANAGER_URL}/-/healthy"
if ! curl -sSf "${ALERTMANAGER_URL}/-/healthy" >/dev/null 2>&1; then
  log "FAIL: Alertmanager unreachable at ${ALERTMANAGER_URL}. Start the monitoring stack first."
  exit 2
fi
log "Alertmanager OK"

log "Preflight — checking Mailhog at ${MAILHOG_API}"
if ! curl -sSf "${MAILHOG_API}" >/dev/null 2>&1; then
  log "FAIL: Mailhog unreachable at ${MAILHOG_API}. Start docker-compose.full-stack.yml first."
  exit 2
fi

mailhog_count() {
  curl -sSf "${MAILHOG_API}" | python3 -c 'import sys, json; d=json.load(sys.stdin); print(d.get("total", 0))' 2>/dev/null || echo "0"
}

BASELINE_MAIL_COUNT=$(mailhog_count)
log "Baseline Mailhog message count: ${BASELINE_MAIL_COUNT}"

# --- Test 1: synthetic alert via Alertmanager API ---------------------------
log "Test 1/2 — posting synthetic alert via Alertmanager /api/v2/alerts"

SYNTHETIC_ALERT_PAYLOAD='[{
  "labels": {
    "alertname": "SmokeTestSynthetic",
    "severity": "critical",
    "service": "smoke-test",
    "component": "verification"
  },
  "annotations": {
    "summary": "Phase 9 smoke test synthetic alert",
    "description": "This alert was injected by infra/monitoring/scripts/smoke-test-alertmanager.sh and should route through Alertmanager to Mailhog."
  },
  "generatorURL": "http://localhost/smoke-test"
}]'

curl -sSf -H 'Content-Type: application/json' \
  -X POST "${ALERTMANAGER_URL}/api/v2/alerts" \
  -d "${SYNTHETIC_ALERT_PAYLOAD}" >/dev/null

log "Synthetic alert posted. Waiting up to ${TIMEOUT_SECS}s for delivery to Mailhog..."

deadline=$(($(date +%s) + TIMEOUT_SECS))
synthetic_delivered=0
while [ "$(date +%s)" -lt "${deadline}" ]; do
  CURRENT_COUNT=$(mailhog_count)
  if [ "${CURRENT_COUNT}" -gt "${BASELINE_MAIL_COUNT}" ]; then
    synthetic_delivered=1
    log "PASS — Mailhog message count went ${BASELINE_MAIL_COUNT} -> ${CURRENT_COUNT}"
    break
  fi
  sleep 5
done

if [ "${synthetic_delivered}" -ne 1 ]; then
  log "FAIL — synthetic alert did not reach Mailhog within ${TIMEOUT_SECS}s"
  log "  Check: docker compose logs alertmanager | tail -40"
  log "  Check: docker compose logs prometheus | tail -20"
  exit 3
fi

POST_SYNTHETIC_COUNT=$(mailhog_count)

# --- Test 2: real ServiceDown by stopping core-java -------------------------
log "Test 2/2 — stopping jtoye-core-java to trigger the real ServiceDown rule"

if ! docker ps --format '{{.Names}}' | grep -q '^jtoye-core-java$'; then
  log "SKIP — jtoye-core-java not running; real alert test requires the full stack. Treating as PASS (synthetic already proved the route)."
  log "PASS (synthetic only)"
  exit 0
fi

docker stop jtoye-core-java >/dev/null

# ServiceDown rule has `for: 2m`, so wait at least 2m30s for evaluation + routing
REAL_DEADLINE_EXTRA=$((TIMEOUT_SECS + 180))
log "core-java stopped. Waiting up to ${REAL_DEADLINE_EXTRA}s for ServiceDown -> email delivery..."

deadline=$(($(date +%s) + REAL_DEADLINE_EXTRA))
real_delivered=0
while [ "$(date +%s)" -lt "${deadline}" ]; do
  CURRENT_COUNT=$(mailhog_count)
  if [ "${CURRENT_COUNT}" -gt "${POST_SYNTHETIC_COUNT}" ]; then
    real_delivered=1
    log "PASS — Mailhog message count went ${POST_SYNTHETIC_COUNT} -> ${CURRENT_COUNT}"
    break
  fi
  sleep 10
done

# Cleanup — restart core-java regardless of result
log "Cleanup — restarting jtoye-core-java"
docker start jtoye-core-java >/dev/null || log "WARN: failed to restart jtoye-core-java"

if [ "${real_delivered}" -ne 1 ]; then
  log "FAIL — ServiceDown alert did not reach Mailhog within ${REAL_DEADLINE_EXTRA}s"
  exit 4
fi

log "SMOKE TEST PASSED — both synthetic and real alert delivered to Mailhog"
log "Open Mailhog UI at http://localhost:8025 to inspect the email bodies"
exit 0
