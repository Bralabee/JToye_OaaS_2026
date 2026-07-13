#!/usr/bin/env bash
#
# e2e-rls.sh — live cross-tenant RLS proof THROUGH the MCP tool (Phase 20 / AI-1).
#
# The load-bearing isolation test. Superuser Testcontainers cannot prove RLS
# (RESEARCH Pitfall 4), so this exercises two genuinely tenant-scoped tokens at
# the HTTP boundary through the same list_products tool and asserts DISJOINT,
# NON-EMPTY, BIDIRECTIONAL results against disjoint seeded data:
#
#   token A (tenant-a-user)  →  list_products  →  CONTAINS the DemoDataSeeder
#                                                  tenant-A catalogue (marker
#                                                  MAK-JOL), and does NOT contain
#                                                  the tenant-B probe SKU.
#   token B (tenant-b-user)  →  list_products  →  CONTAINS the tenant-B probe SKU
#                                                  (TENANTB-PROBE-1), and does NOT
#                                                  contain any tenant-A product.
#
# Plus an order-scope negative: token A's read_orders view carries no tenant-B
# marker (tenant B seeds only a probe product, no orders — RESEARCH Q#2), i.e.
# cross-tenant order data never leaks into tenant A's read.
#
# Both tokens are minted via the direct-access PASSWORD grant against the
# confidential `core-api` client, which carries the audience mapper — so a 200
# from core implicitly confirms the #88 aud=core-api gate passed (T-20-02).
#
# Authored in Wave 3 (20-04); RUN LIVE in Wave 4 (20-05) against the rebuilt,
# realm-re-imported dev stack. Compares row IDENTIFIERS only; never echoes a
# token or a full PII response body (T-20-01).
#
# Preconditions for a live run:
#   1. Full container rebuild is up; realm RE-IMPORTED (docs/security-scopes.md §4).
#   2. DemoDataSeeder has run (tenant-A catalogue + tenant-B probe SKU present).
#   3. KC_SEED_USER_PASSWORD exported (or in ./.env). If core-api requires client
#      authentication, KEYCLOAK_CLIENT_SECRET is also read from env when present.
#
# Usage:
#   KC_SEED_USER_PASSWORD=... mcp-server/scripts/e2e-rls.sh
#   ENV_FILE=./.env           mcp-server/scripts/e2e-rls.sh
#
set -euo pipefail

# ---- Config (all overridable via env) --------------------------------------
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8085}"      # host endpoint → iss=localhost:8085 (split-horizon; core accepts)
KC_REALM="${KC_REALM:-jtoye-dev}"
MCP_URL="${MCP_URL:-http://localhost:9100}"
TOKEN_ENDPOINT="${KEYCLOAK_URL}/realms/${KC_REALM}/protocol/openid-connect/token"
MCP_ENDPOINT="${MCP_URL}/mcp"

# Disjoint seeded markers (identifiers, NOT PII) — overridable but defaults are
# the DemoDataSeeder facts from 20-03.
TENANT_A_MARKER="${TENANT_A_MARKER:-MAK-JOL}"             # a tenant-A DemoDataSeeder SKU
TENANT_B_MARKER="${TENANT_B_MARKER:-TENANTB-PROBE-1}"    # the tenant-B probe SKU
TENANT_B_SHOP_MARKER="tenant-b-probe"                    # the tenant-B probe shop slug

# ---- Colored pass/fail helpers (mirror scripts/verify-env.sh) --------------
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; FAILURES=$((FAILURES + 1)); }
info() { echo -e "${YELLOW}ℹ INFO${NC}: $1"; }

ENV_FILE="${ENV_FILE:-./.env}"
if [ -f "$ENV_FILE" ]; then
  set -a; # shellcheck disable=SC1090
  . "$ENV_FILE"; set +a
  info "Loaded env from ${ENV_FILE}"
fi

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

echo "========================================="
echo "MCP e2e — cross-tenant RLS proof"
echo "========================================="
echo "Keycloak: ${TOKEN_ENDPOINT}"
echo "MCP:      ${MCP_ENDPOINT}"
echo ""

FAILURES=0

# ---- (0) Preflight ---------------------------------------------------------
if [ -z "${KC_SEED_USER_PASSWORD:-}" ]; then
  fail "KC_SEED_USER_PASSWORD is unset/empty — export it or add it to ${ENV_FILE} (never hardcode it)."
  exit 1
fi
pass "KC_SEED_USER_PASSWORD is set (value hidden)"

HEALTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${MCP_URL}/health" 2>/dev/null || echo 000)"
if [ "$HEALTH_CODE" != "200" ]; then
  fail "MCP /health returned ${HEALTH_CODE} (expected 200). Rebuild ALL containers + re-import the realm (docs/security-scopes.md §4)."
  exit 1
fi
pass "MCP server healthy (GET /health → 200)"

# ---- Helpers ---------------------------------------------------------------

# Mint a direct-access PASSWORD-grant token for a tenant user against core-api.
# core-api is confidential and carries the audience mapper; when the client
# secret is present in the env we send it so the token endpoint authenticates
# the client. Writes the token JSON to $2. Echoes the HTTP status.
mint_password_token() { # <username> <outfile>
  local user="$1" outfile="$2"
  local -a form=(
    -d 'grant_type=password'
    -d 'client_id=core-api'
    -d "username=${user}"
    --data-urlencode "password=${KC_SEED_USER_PASSWORD}"
  )
  if [ -n "${KEYCLOAK_CLIENT_SECRET:-}" ]; then
    form+=( --data-urlencode "client_secret=${KEYCLOAK_CLIENT_SECRET}" )
  fi
  curl -s -o "$outfile" -w '%{http_code}' --max-time 10 \
    -X POST "$TOKEN_ENDPOINT" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    "${form[@]}" 2>/dev/null || echo 000
}

# Call an MCP tool with a Bearer token. Writes the raw (SSE) response to $3.
# Echoes the HTTP status. Never prints the token or the body.
mcp_call() { # <token> <rpc_body> <outfile>
  local token="$1" body="$2" outfile="$3"
  curl -s -o "$outfile" -w '%{http_code}' --max-time 15 \
    -X POST "$MCP_ENDPOINT" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Authorization: Bearer ${token}" \
    -d "$body" 2>/dev/null || echo 000
}

# Extract the tool result's inner text (the stringified core JSON) from an SSE
# response file. Empty on error/absence. Never printed by the caller.
inner_text() { # <outfile>
  local data
  data="$(grep -m1 '^data: ' "$1" | sed 's/^data: //' || true)"
  [ -z "$data" ] && return 0
  printf '%s' "$data" | jq -r '.result.content[0].text // empty' 2>/dev/null || true
}

LIST_PRODUCTS='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_products","arguments":{"size":100}}}'
READ_ORDERS='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_orders","arguments":{}}}'

# ---- (1) Mint the two tenant-scoped tokens ---------------------------------
STATUS_A="$(mint_password_token 'tenant-a-user' "${WORK}/tokenA.json")"
TOKEN_A="$(jq -r '.access_token // empty' "${WORK}/tokenA.json" 2>/dev/null || true)"
if [ "$STATUS_A" != "200" ] || [ -z "$TOKEN_A" ]; then
  ERR="$(jq -r '.error // "unknown"' "${WORK}/tokenA.json" 2>/dev/null || echo unknown)"
  fail "tenant-a-user token mint failed (HTTP ${STATUS_A}, error=${ERR}). 'invalid_client' → set KEYCLOAK_CLIENT_SECRET / re-import realm."
  exit 1
fi
pass "Minted tenant-a-user password-grant token (token hidden)"

STATUS_B="$(mint_password_token 'tenant-b-user' "${WORK}/tokenB.json")"
TOKEN_B="$(jq -r '.access_token // empty' "${WORK}/tokenB.json" 2>/dev/null || true)"
if [ "$STATUS_B" != "200" ] || [ -z "$TOKEN_B" ]; then
  ERR="$(jq -r '.error // "unknown"' "${WORK}/tokenB.json" 2>/dev/null || echo unknown)"
  fail "tenant-b-user token mint failed (HTTP ${STATUS_B}, error=${ERR})."
  exit 1
fi
pass "Minted tenant-b-user password-grant token (token hidden)"

# ---- (2) list_products through the MCP tool for each tenant ----------------
MCP_A="$(mcp_call "$TOKEN_A" "$LIST_PRODUCTS" "${WORK}/prodA.out")"
MCP_B="$(mcp_call "$TOKEN_B" "$LIST_PRODUCTS" "${WORK}/prodB.out")"
if [ "$MCP_A" != "200" ]; then fail "list_products (tenant A) → HTTP ${MCP_A} (expected 200). A token likely lacks aud=core-api (#88)."; exit 1; fi
if [ "$MCP_B" != "200" ]; then fail "list_products (tenant B) → HTTP ${MCP_B} (expected 200)."; exit 1; fi
pass "Both tenants reached list_products through the MCP tool (HTTP 200 + aud gate)"

INNER_A="$(inner_text "${WORK}/prodA.out")"
INNER_B="$(inner_text "${WORK}/prodB.out")"
if [ -z "$INNER_A" ]; then fail "tenant A list_products returned empty content (expected the DemoDataSeeder catalogue)."; exit 1; fi
if [ -z "$INNER_B" ]; then fail "tenant B list_products returned empty content (expected the probe SKU)."; exit 1; fi

# ---- (3) Disjoint, bidirectional, non-empty assertions ---------------------
# Tenant A: contains A-marker, NOT the tenant-B probe SKU.
if printf '%s' "$INNER_A" | grep -Fq "$TENANT_A_MARKER"; then
  pass "tenant A result CONTAINS the tenant-A marker (${TENANT_A_MARKER})"
else
  fail "tenant A result is MISSING the tenant-A marker (${TENANT_A_MARKER}) — seed not applied?"
fi
if printf '%s' "$INNER_A" | grep -Fq "$TENANT_B_MARKER"; then
  fail "RLS LEAK: tenant A result CONTAINS the tenant-B probe SKU (${TENANT_B_MARKER})."
else
  pass "tenant A result does NOT contain the tenant-B probe SKU (isolation holds)"
fi

# Tenant B: contains the probe SKU, NOT any tenant-A product.
if printf '%s' "$INNER_B" | grep -Fq "$TENANT_B_MARKER"; then
  pass "tenant B result CONTAINS the tenant-B probe SKU (${TENANT_B_MARKER})"
else
  fail "tenant B result is MISSING the probe SKU (${TENANT_B_MARKER}) — tenant-B seed not applied?"
fi
if printf '%s' "$INNER_B" | grep -Fq "$TENANT_A_MARKER"; then
  fail "RLS LEAK: tenant B result CONTAINS a tenant-A product (${TENANT_A_MARKER})."
else
  pass "tenant B result does NOT contain any tenant-A product (isolation holds)"
fi

# ---- (4) Order-scope negative through read_orders --------------------------
# Tenant B seeds only a probe product (no orders — RESEARCH Q#2), so a concrete
# tenant-B order id is not available. Per the plan's fallback we assert token A's
# read_orders view carries NO tenant-B marker: cross-tenant order data never
# leaks into tenant A's read.
ORD_A="$(mcp_call "$TOKEN_A" "$READ_ORDERS" "${WORK}/ordersA.out")"
if [ "$ORD_A" != "200" ]; then
  fail "read_orders (tenant A) → HTTP ${ORD_A} (expected 200)."
else
  if grep -Fq "$TENANT_B_MARKER" "${WORK}/ordersA.out" || grep -Fq "$TENANT_B_SHOP_MARKER" "${WORK}/ordersA.out"; then
    fail "RLS LEAK: tenant A read_orders view contains a tenant-B marker (${TENANT_B_MARKER}/${TENANT_B_SHOP_MARKER})."
  else
    pass "tenant A read_orders view contains NO tenant-B marker (no cross-tenant order leak)"
  fi
fi

# ---- Summary ---------------------------------------------------------------
echo ""
if [ "$FAILURES" -eq 0 ]; then
  pass "Cross-tenant RLS proof PASSED — disjoint, non-empty, bidirectional isolation through the MCP tool."
  exit 0
else
  fail "Cross-tenant RLS proof FAILED (${FAILURES} problem(s)) — investigate a possible isolation leak."
  exit 1
fi
