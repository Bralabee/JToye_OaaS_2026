#!/bin/bash
# verify-env.sh — environment-variable preflight for JToye OaaS (issue #80)
#
# USAGE:
#   scripts/verify-env.sh [ENV_FILE] [--with-stack]
#     ENV_FILE      Path to the env file to validate (default: ./.env)
#     --with-stack  ALSO run the live running-stack smoke tests (health / DB /
#                   Flyway / RLS). Default is a pure, stack-INDEPENDENT env check.
#
# WIRING:
#   Runs as a fail-loud preflight at the top of scripts/start-dev.sh, BEFORE any
#   container is brought up. Exits non-zero (naming the offending variable) when a
#   required credential is unset/empty, matches the weak deny-list, or is shorter
#   than the minimum length, so the stack never boots with a missing or weak
#   secret. Live stack health is covered separately by scripts/smoke-test.sh, and
#   network exposure by scripts/check-infra-exposure.sh.
#
# SECURITY: never prints secret VALUES — variable NAMES only.

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; }
info() { echo -e "${YELLOW}ℹ INFO${NC}: $1"; }

# ---- Config: maintain these two lists ---------------------------------------
# Required credential variables — must be set, non-empty, and non-weak.
REQUIRED_VARS=(
  POSTGRES_PASSWORD
  KEYCLOAK_ADMIN_PASSWORD
  KC_ADMIN_PASSWORD
  KC_DB_PASSWORD
  REDIS_PASSWORD
  RABBITMQ_DEFAULT_PASS
  DB_PASSWORD
  RABBITMQ_PASSWORD
  MINIO_ROOT_USER
  MINIO_ROOT_PASSWORD
  NEXTAUTH_SECRET
  KEYCLOAK_CLIENT_SECRET
  EDGE_API_CLIENT_SECRET
  KC_SEED_USER_PASSWORD
  INTEGRATION_CATALOG_RO_SECRET
  INTEGRATION_ORDERS_RW_SECRET
  # Added for issues #438 / #439. Both were previously OUTSIDE this list, which
  # is the whole reason they went unnoticed: the monitoring compose file requires
  # them (`${VAR:?}`) so they were always SET, and being set was all anyone ever
  # checked. This preflight is the control that should have fired, and it could
  # not fire on a variable it was not looking at.
  GRAFANA_ADMIN_PASSWORD
  POSTGRES_EXPORTER_PASSWORD
)

# Weak values that must never be used. Tokens are stored canonical UPPER-case and
# compared case-insensitively (the candidate value is lower-cased before the
# comparison), so this control never embeds a lower-case weak literal. In addition
# to these exact tokens, any value beginning with CHANGE_ME and any value ending in
# the leaked dev client-secret suffix (a "-secret-2026" tail) is treated as weak.
DENY_EXACT=(
  ADMIN123
  PASSWORD123
  MINIOADMIN
  CHANGEME
  # Added for issues #438 / #439 — both defects were a single dictionary word
  # sitting in a variable this list did not name. Enumerating tokens can only
  # ever catch the ones somebody thought of, so the length floor below is the
  # control that generalises; this list stays for the well-known factory pairs.
  ADMIN
  SECRET
  PASSWORD
  GUEST
  ROOT
  POSTGRES
  REDIS
  KEYCLOAK
  GRAFANA
  LETMEIN
)

# Minimum length for a required credential. This is deliberately a SEPARATE
# mechanism from the token list above: it catches a weak value nobody enumerated.
# Both measured defects (a 6-letter and a 5-letter word) fail this floor on their
# length alone, without the deny-list naming either of them.
MIN_CREDENTIAL_LENGTH=8

# ---- Arg parsing ------------------------------------------------------------
ENV_FILE="./.env"
WITH_STACK=0
for arg in "$@"; do
  case "$arg" in
    --with-stack) WITH_STACK=1 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) ENV_FILE="$arg" ;;
  esac
done

echo "========================================="
echo "JToye OaaS Environment Preflight"
echo "========================================="
echo "Env file: ${ENV_FILE}"
echo ""

ERRORS=0

# ---- Load env file ----------------------------------------------------------
if [ ! -f "$ENV_FILE" ]; then
  fail "Env file not found: ${ENV_FILE}"
  exit 1
fi
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

# ---- (a) required + non-empty -----------------------------------------------
echo "Checking required credential variables are set and non-empty..."
for var in "${REQUIRED_VARS[@]}"; do
  val="${!var}"
  if [ -z "$val" ]; then
    fail "Required variable ${var} is unset or empty"
    ERRORS=$((ERRORS + 1))
  fi
done

# ---- (b) weak deny-list -----------------------------------------------------
echo "Checking no required variable uses a weak / deny-listed value..."
is_weak() {
  local v_lc
  v_lc=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
  local d d_lc
  for d in "${DENY_EXACT[@]}"; do
    d_lc=$(printf '%s' "$d" | tr '[:upper:]' '[:lower:]')
    [ "$v_lc" = "$d_lc" ] && return 0
  done
  case "$v_lc" in
    change_me*) return 0 ;;
    *secret-2026) return 0 ;;
  esac
  return 1
}
for var in "${REQUIRED_VARS[@]}"; do
  val="${!var}"
  [ -z "$val" ] && continue # already reported as missing above
  if is_weak "$val"; then
    fail "Required variable ${var} matches the weak deny-list (value redacted)"
    ERRORS=$((ERRORS + 1))
  fi
done

# ---- (c) minimum length -----------------------------------------------------
echo "Checking no required variable is shorter than ${MIN_CREDENTIAL_LENGTH} characters..."
for var in "${REQUIRED_VARS[@]}"; do
  val="${!var}"
  [ -z "$val" ] && continue # already reported as missing above
  if [ "${#val}" -lt "$MIN_CREDENTIAL_LENGTH" ]; then
    fail "Required variable ${var} is shorter than the ${MIN_CREDENTIAL_LENGTH}-character minimum (value redacted)"
    ERRORS=$((ERRORS + 1))
  fi
done

# ---- (d) cross-variable consistency -----------------------------------------
# Checks (a)-(c) validate each variable IN ISOLATION. That is not enough, and the
# gap had a real cost: e2e-nightly.yml generated an independent random value for
# POSTGRES_PASSWORD and for KC_DB_PASSWORD, both of which name the SAME Postgres
# role (POSTGRES_USER=jtoye, KC_DB_USERNAME=jtoye). Every variable was set,
# non-weak and long enough, so this preflight passed — and the stack then died at
# `FATAL: password authentication failed for user "jtoye"` roughly four minutes
# later, on every scheduled run the workflow ever had.
#
# It is the same lesson as the #438/#439 note above, one level up: the control
# could not fire on a RELATIONSHIP it was not looking at. A structural check can
# pass while the thing it exists to protect is broken.
#
# Each entry is "userVarA:passVarA|userVarB:passVarB" and means: if the two user
# variables name the same role, the two password variables must agree.
echo "Checking credential pairs that name the same role actually agree..."
CREDENTIAL_PAIRS=(
  "POSTGRES_USER:POSTGRES_PASSWORD|KC_DB_USERNAME:KC_DB_PASSWORD"
)
PAIRS_CHECKED=0
for pair in "${CREDENTIAL_PAIRS[@]}"; do
  left="${pair%%|*}"; right="${pair##*|}"
  u_a="${left%%:*}";  p_a="${left##*:}"
  u_b="${right%%:*}"; p_b="${right##*:}"
  val_u_a="${!u_a-}"; val_u_b="${!u_b-}"
  val_p_a="${!p_a-}"; val_p_b="${!p_b-}"

  # An unset user variable is not "no conflict" — it means this check could not
  # be evaluated, and a check that cannot be evaluated must say so, never pass
  # silently. "Found nothing" is never "clean".
  if [ -z "$val_u_a" ] || [ -z "$val_u_b" ]; then
    fail "Cannot evaluate the ${p_a}/${p_b} pairing: ${u_a} and/or ${u_b} is unset"
    ERRORS=$((ERRORS + 1))
    continue
  fi

  PAIRS_CHECKED=$((PAIRS_CHECKED + 1))
  if [ "$val_u_a" = "$val_u_b" ] && [ "$val_p_a" != "$val_p_b" ]; then
    fail "${u_a} and ${u_b} are both '${val_u_a}' — the same database role — but ${p_a} and ${p_b} differ (values redacted). Postgres creates that role with ${p_a}; anything connecting with ${p_b} will get 'password authentication failed'."
    ERRORS=$((ERRORS + 1))
  fi
done
[ "$PAIRS_CHECKED" -gt 0 ] || { fail "No credential pairs were evaluated — this check is vacuous"; ERRORS=$((ERRORS + 1)); }

if [ "$ERRORS" -eq 0 ]; then
  pass "All ${#REQUIRED_VARS[@]} required credential variables are set, non-weak and long enough"
  pass "All ${PAIRS_CHECKED} same-role credential pair(s) agree"
else
  echo ""
  fail "${ERRORS} environment problem(s) found — fix the named variable(s) in ${ENV_FILE}"
  exit 1
fi

# ---- Optional: running-stack smoke tests (--with-stack) ---------------------
if [ "$WITH_STACK" -eq 0 ]; then
  echo ""
  info "Skipping running-stack tests (pass --with-stack to include health / DB / Flyway / RLS)."
  exit 0
fi

echo ""
echo "========================================="
echo "Running-stack smoke tests (--with-stack)"
echo "========================================="
STACK_FAILED=0

# Test: Actuator Health
ACTUATOR_STATUS=$(curl -s http://localhost:9090/actuator/health | jq -r .status 2>/dev/null)
if [ "$ACTUATOR_STATUS" = "UP" ]; then
  pass "Actuator health shows UP"
else
  fail "Actuator health failed. Got: ${ACTUATOR_STATUS}"
  STACK_FAILED=$((STACK_FAILED + 1))
fi

# Test: Protected endpoint requires auth
SHOP_NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9090/shops)
if [ "$SHOP_NO_AUTH" = "401" ] || [ "$SHOP_NO_AUTH" = "403" ]; then
  pass "Protected endpoints require authentication (HTTP ${SHOP_NO_AUTH})"
else
  fail "Protected endpoint should return 401/403 without auth. Got: ${SHOP_NO_AUTH}"
  STACK_FAILED=$((STACK_FAILED + 1))
fi

# Test: Database connection
#
# The table is `tenants`, plural. This read `tenant` for as long as the check has
# existed, so the query always errored, `2>/dev/null` swallowed the error, the
# result was always empty and this test could only ever FAIL. It was found while
# verifying that start-dev.sh still works after the #438/#441 rebind — the
# failure is unrelated to that change and predates it. Noted rather than quietly
# corrected because it is the same shape as the defects those issues describe: a
# check that ran for months, was incapable of passing, and was never read.
DB_TEST=$(docker exec jtoye-postgres psql -U "${POSTGRES_USER:-jtoye}" -d "${POSTGRES_DB:-jtoye}" -tAc "SELECT COUNT(*) FROM tenants;" 2>/dev/null)
if [ -n "$DB_TEST" ]; then
  pass "Database is accessible and tenants table exists"
  info "Current tenant count: ${DB_TEST}"
else
  fail "Database connection or schema issue"
  STACK_FAILED=$((STACK_FAILED + 1))
fi

# Test: Flyway migrations applied
MIGRATION_COUNT=$(docker exec jtoye-postgres psql -U "${POSTGRES_USER:-jtoye}" -d "${POSTGRES_DB:-jtoye}" -tAc "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true;" 2>/dev/null)
if [ -n "$MIGRATION_COUNT" ] && [ "$MIGRATION_COUNT" -gt 0 ] 2>/dev/null; then
  pass "Flyway migrations applied (${MIGRATION_COUNT} successful)"
else
  fail "No successful Flyway migrations found"
  STACK_FAILED=$((STACK_FAILED + 1))
fi

# Test: RLS policies installed
RLS_COUNT=$(docker exec jtoye-postgres psql -U "${POSTGRES_USER:-jtoye}" -d "${POSTGRES_DB:-jtoye}" -tAc "SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public';" 2>/dev/null)
if [ -n "$RLS_COUNT" ] && [ "$RLS_COUNT" -gt 0 ] 2>/dev/null; then
  pass "RLS policies are installed (${RLS_COUNT} policies)"
else
  fail "No RLS policies found"
  STACK_FAILED=$((STACK_FAILED + 1))
fi

echo ""
if [ "$STACK_FAILED" -eq 0 ]; then
  pass "All running-stack smoke tests passed"
  exit 0
else
  fail "${STACK_FAILED} running-stack smoke test(s) failed"
  exit 1
fi
