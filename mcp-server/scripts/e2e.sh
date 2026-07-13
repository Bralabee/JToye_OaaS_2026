#!/usr/bin/env bash
#
# e2e.sh — live read happy-path for the MCP server (Phase 20 / AI-1).
#
# Proves, through the MCP tool boundary (NOT a raw core curl), that a caller
# holding the locked reference machine credential (integration-catalog-ro) can
# read the calling tenant's product catalogue:
#
#   integration-catalog-ro client-credentials token  →  POST /mcp list_products
#   →  HTTP 200  →  non-empty, non-error tool result (tenant-A rows).
#
# Authored in Wave 3 (20-04); RUN LIVE in Wave 4 (20-05) against the rebuilt,
# realm-re-imported dev stack. It authors no secret and echoes no token or PII
# response body — it logs status + a row count only (T-20-01).
#
# Preconditions for a live run:
#   1. Full container rebuild (mcp-server + core-java + keycloak) is up.
#   2. The Keycloak realm has been RE-IMPORTED so integration-catalog-ro exists
#      (see docs/security-scopes.md §4). Without it the mint returns
#      invalid_client and this script fails fast with that hint.
#   3. INTEGRATION_CATALOG_RO_SECRET is exported (or present in ./.env).
#
# Usage:
#   INTEGRATION_CATALOG_RO_SECRET=... mcp-server/scripts/e2e.sh
#   ENV_FILE=./.env                   mcp-server/scripts/e2e.sh   # sources .env
#
set -euo pipefail

# ---- Config (all overridable via env) --------------------------------------
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8085}"      # host endpoint → iss=localhost:8085 (split-horizon; core accepts)
KC_REALM="${KC_REALM:-jtoye-dev}"
MCP_URL="${MCP_URL:-http://localhost:9100}"
TOKEN_ENDPOINT="${KEYCLOAK_URL}/realms/${KC_REALM}/protocol/openid-connect/token"
MCP_ENDPOINT="${MCP_URL}/mcp"
CLIENT_ID="integration-catalog-ro"

# ---- Colored pass/fail helpers (mirror scripts/verify-env.sh) --------------
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}✓ PASS${NC}: $1"; }
fail() { echo -e "${RED}✗ FAIL${NC}: $1"; }
info() { echo -e "${YELLOW}ℹ INFO${NC}: $1"; }

# ---- Optionally source an env file so secrets need not be pre-exported ------
ENV_FILE="${ENV_FILE:-./.env}"
if [ -f "$ENV_FILE" ]; then
  set -a; # shellcheck disable=SC1090
  . "$ENV_FILE"; set +a
  info "Loaded env from ${ENV_FILE}"
fi

# Scratch for response bodies (never printed); cleaned on exit.
WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

echo "========================================="
echo "MCP e2e — read happy-path (list_products)"
echo "========================================="
echo "Keycloak: ${TOKEN_ENDPOINT}"
echo "MCP:      ${MCP_ENDPOINT}"
echo ""

FAILURES=0

# ---- (0) Preflight: required secret + MCP health ---------------------------
if [ -z "${INTEGRATION_CATALOG_RO_SECRET:-}" ]; then
  fail "INTEGRATION_CATALOG_RO_SECRET is unset/empty — export it or add it to ${ENV_FILE} (never hardcode it)."
  exit 1
fi
pass "INTEGRATION_CATALOG_RO_SECRET is set (value hidden)"

HEALTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${MCP_URL}/health" 2>/dev/null || echo 000)"
if [ "$HEALTH_CODE" != "200" ]; then
  fail "MCP /health returned ${HEALTH_CODE} (expected 200). Rebuild ALL containers and re-import the realm, then retry (docs/security-scopes.md §4)."
  exit 1
fi
pass "MCP server healthy (GET /health → 200)"

# ---- (1) Mint the reference client-credentials token -----------------------
TOKEN_STATUS="$(curl -s -o "${WORK}/token.json" -w '%{http_code}' --max-time 10 \
  -X POST "$TOKEN_ENDPOINT" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials' \
  -d 'client_id=integration-catalog-ro' \
  --data-urlencode "client_secret=${INTEGRATION_CATALOG_RO_SECRET}" 2>/dev/null || echo 000)"

ACCESS_TOKEN="$(jq -r '.access_token // empty' "${WORK}/token.json" 2>/dev/null || true)"
if [ "$TOKEN_STATUS" != "200" ] || [ -z "$ACCESS_TOKEN" ]; then
  ERR="$(jq -r '.error // "unknown"' "${WORK}/token.json" 2>/dev/null || echo unknown)"
  fail "Token mint failed (HTTP ${TOKEN_STATUS}, error=${ERR}). 'invalid_client' → realm not re-imported / wrong secret (docs/security-scopes.md §4)."
  exit 1
fi
pass "Minted ${CLIENT_ID} client-credentials token (token hidden)"

# ---- (2) Call the MCP list_products tool -----------------------------------
RPC_BODY='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_products","arguments":{}}}'
MCP_STATUS="$(curl -s -o "${WORK}/mcp.out" -w '%{http_code}' --max-time 15 \
  -X POST "$MCP_ENDPOINT" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -d "$RPC_BODY" 2>/dev/null || echo 000)"

if [ "$MCP_STATUS" != "200" ]; then
  fail "MCP list_products returned HTTP ${MCP_STATUS} (expected 200)."
  exit 1
fi
pass "MCP POST /mcp list_products → HTTP 200"

# The stateless Streamable HTTP transport frames the reply as an SSE event; the
# JSON-RPC payload is the single 'data:' line. Extract it (never print it).
DATA_LINE="$(grep -m1 '^data: ' "${WORK}/mcp.out" | sed 's/^data: //' || true)"
if [ -z "$DATA_LINE" ]; then
  fail "No SSE data line in the MCP response (unexpected transport framing)."
  exit 1
fi

IS_ERROR="$(printf '%s' "$DATA_LINE" | jq -r '.result.isError // false' 2>/dev/null || echo true)"
if [ "$IS_ERROR" = "true" ]; then
  fail "Tool returned isError=true (core rejected the read or was unreachable)."
  exit 1
fi
pass "Tool result is not an error (isError absent/false)"

INNER="$(printf '%s' "$DATA_LINE" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)"
if [ -z "$INNER" ] || [ "$INNER" = "null" ]; then
  fail "Tool result content is empty."
  exit 1
fi

# Count rows shape-agnostically: Spring Page ({content:[...]}) or a bare array.
ROWS="$(printf '%s' "$INNER" | jq '(.content // .) | if type=="array" then length else 0 end' 2>/dev/null || echo 0)"
if ! [ "$ROWS" -gt 0 ] 2>/dev/null; then
  fail "list_products returned no rows (expected tenant-A catalogue rows)."
  exit 1
fi
pass "list_products returned ${ROWS} product row(s) for the tenant (non-empty)"

# ---- Summary ---------------------------------------------------------------
echo ""
if [ "$FAILURES" -eq 0 ]; then
  pass "E2E read happy-path PASSED — integration-catalog-ro reads its tenant catalogue through the MCP tool."
  exit 0
else
  fail "E2E read happy-path FAILED (${FAILURES} problem(s))."
  exit 1
fi
