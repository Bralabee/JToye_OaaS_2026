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
#        (or synthetic only, when ALLOW_SYNTHETIC_ONLY=1 was set deliberately)
#   2  — Alertmanager unreachable (setup problem)
#   3  — synthetic alert did not reach Mailhog within the timeout
#   4  — real ServiceDown test did not reach Mailhog within the timeout
#   5  — VOID: the core-java container could not be resolved or is not running, so
#        the real ServiceDown path was never exercised. This is NOT a pass. See the
#        `resolve_core_container` comment below for why this used to exit 0.
#
# Run from repo root.

set -eu

ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
MAILHOG_API="${MAILHOG_API:-http://localhost:8025/api/v2/messages}"
TIMEOUT_SECS="${TIMEOUT_SECS:-90}"

# Which stack to interrogate, and which service within it. Configuration, not
# literals — same ${VAR:-default} convention as infra/backups/backup.sh,
# scripts/seed-e2e-fixtures.sh and infra/load-testing/baseline.sh.
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.full-stack.yml}"
CORE_SERVICE="${CORE_SERVICE:-core-java}"
# Explicit container override; wins over compose resolution when set.
CORE_CONTAINER="${CORE_CONTAINER:-}"
# Set to 1 to DELIBERATELY accept synthetic-only coverage (e.g. running the
# monitoring stack without the app stack). Absent this, an unresolvable core-java
# is a VOID, never a pass.
ALLOW_SYNTHETIC_ONLY="${ALLOW_SYNTHETIC_ONLY:-0}"

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

# Resolve the container through COMPOSE, never by a literal container name.
#
# docker-compose.full-stack.yml deliberately removed `container_name` from
# core-java so the service can be run as `--scale core-java=N`. The container is
# therefore named by compose (project + service + index, e.g.
# `jtoye_oaas_2026-core-java-1`), and the literal `jtoye-core-java` this script
# used to match CANNOT EXIST. Measured 2026-08-02 with core-java running+healthy:
# `docker ps --filter 'name=^jtoye-core-java$'` returned empty. Combined with a
# no-match branch that exited 0, Test 2 had been silently dead and reported as a
# pass on every run since container_name was removed.
#
# scripts/check-container-config-drift.sh resolves service -> container id through
# compose for this exact reason; this is the same approach.
# infra/load-testing/media-pipeline-arm.sh carries the matching warning.
resolve_core_container() {
  if [ -n "${CORE_CONTAINER}" ]; then
    printf '%s\n' "${CORE_CONTAINER}"
    return 0
  fi
  docker compose -f "${COMPOSE_FILE}" ps -q "${CORE_SERVICE}" 2>/dev/null | head -1
}

log "Test 2/2 — stopping compose service '${CORE_SERVICE}' to trigger the real ServiceDown rule"

CORE_ID=$(resolve_core_container)
CORE_STATE=""
if [ -n "${CORE_ID}" ]; then
  CORE_STATE=$(docker inspect --format '{{.State.Status}}' "${CORE_ID}" 2>/dev/null || echo "")
fi

if [ -z "${CORE_ID}" ] || [ "${CORE_STATE}" != "running" ]; then
  if [ -z "${CORE_ID}" ]; then
    REASON="no container resolved for compose service '${CORE_SERVICE}' in ${COMPOSE_FILE}"
  else
    REASON="container ${CORE_ID} state is '${CORE_STATE:-unknown}', not 'running'"
  fi

  if [ "${ALLOW_SYNTHETIC_ONLY}" = "1" ]; then
    log "SKIP — ${REASON}; ALLOW_SYNTHETIC_ONLY=1 was set deliberately."
    log "PARTIAL — the synthetic route is proven; the real ServiceDown path was NOT exercised."
    exit 0
  fi

  log "VOID — ${REASON}."
  log "  This is NOT a pass: Prometheus rule evaluation -> Alertmanager routing was never exercised."
  log "  Fix by one of:"
  log "    - start the full stack (docker compose -f ${COMPOSE_FILE} up -d ${CORE_SERVICE})"
  log "    - set CORE_SERVICE=<compose service> or CORE_CONTAINER=<container name/id>"
  log "    - set ALLOW_SYNTHETIC_ONLY=1 to accept synthetic-only coverage deliberately"
  exit 5
fi

CORE_NAME=$(docker inspect --format '{{.Name}}' "${CORE_ID}" 2>/dev/null || echo "${CORE_ID}")
log "Resolved ${CORE_SERVICE} -> ${CORE_NAME} (${CORE_ID}), state=${CORE_STATE}"

docker stop "${CORE_ID}" >/dev/null

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
log "Cleanup — restarting ${CORE_NAME}"
docker start "${CORE_ID}" >/dev/null || log "WARN: failed to restart ${CORE_NAME} (${CORE_ID})"

if [ "${real_delivered}" -ne 1 ]; then
  log "FAIL — ServiceDown alert did not reach Mailhog within ${REAL_DEADLINE_EXTRA}s"
  exit 4
fi

log "SMOKE TEST PASSED — both synthetic and real alert delivered to Mailhog"
log "Open Mailhog UI at http://localhost:8025 to inspect the email bodies"
exit 0
