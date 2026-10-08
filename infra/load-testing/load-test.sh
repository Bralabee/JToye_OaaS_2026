#!/bin/bash
#
# JToye OaaS Load Testing Script
# Uses Apache Bench (ab) or hey for load testing
#
# Purpose: Establish capacity limits and performance baselines
#
# SEE ALSO: ./baseline.sh and ./README.md  (added by phase 27, plan 27-00 Task 6)
#
#   This script ASSERTS NO STATUS CODE. That matters more than it sounds: an
#   unauthenticated GET /api/v1/shops returns 401, and a 401 flood was measured here
#   at 3395 req/s. Read without checking the status distribution, that looks like an
#   excellent throughput result. baseline.sh fails on any non-2xx for this reason.
#
#   Two further things measured 2026-07-27, both of which mean this script could not
#   have produced a number on the dev host as written:
#     - it requests a token with client_id=core-api and NO client secret, but core-api
#       is a CONFIDENTIAL client, so Keycloak rejects every attempt;
#     - no HTTP load tool (hey/ab/k6/wrk/vegeta) was installed at all, so check_tools
#       exited 1 before any request was sent.
#
#   This file is deliberately left FUNCTIONALLY UNCHANGED — it is working prior art,
#   and baseline.sh reuses its KC_SEED_USER_PASSWORD handling verbatim. Only comments
#   were added.
#
# D-06 (Phase 37-04): strict scoping is ON by default — an ungranted user has NO access.
#   The seeded tenant-a-user holds no shop_staff grant, and Test 2 (POST /shops, a shop
#   create) needs a tenant-wide GROUP_ADMIN. Grant TEST_USER explicitly on the dashboard's
#   Staff page (GROUP_ADMIN for the write test) before running. The script now checks the
#   user's effective access (GET /api/v1/staff/me) after obtaining a token and exits 1 with
#   that message instead of load-testing a refusal, and exits 1 if the write test sees a 403.
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
API_BASE_URL="${API_BASE_URL:-http://localhost:9090}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8085}"
CONCURRENT_USERS="${CONCURRENT_USERS:-10}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-1000}"

# Test user credentials
TEST_USER="${TEST_USER:-tenant-a-user}"
# Seed-user password comes from the rotated .env (issue #80). Export KC_SEED_USER_PASSWORD
# (or TEST_PASSWORD) before running; never hardcode a literal here.
TEST_PASSWORD="${TEST_PASSWORD:-${KC_SEED_USER_PASSWORD:?KC_SEED_USER_PASSWORD or TEST_PASSWORD must be set}}"

echo -e "${GREEN}=== JToye OaaS Load Testing ===${NC}"
echo ""
echo "Configuration:"
echo "  API URL: $API_BASE_URL"
echo "  Concurrent Users: $CONCURRENT_USERS"
echo "  Total Requests: $TOTAL_REQUESTS"
echo ""

# Check for required tools
check_tools() {
    echo -e "${BLUE}Checking for load testing tools...${NC}"

    if command -v hey &> /dev/null; then
        LOAD_TOOL="hey"
        echo -e "${GREEN}✓ Using 'hey' for load testing${NC}"
    elif command -v ab &> /dev/null; then
        LOAD_TOOL="ab"
        echo -e "${GREEN}✓ Using 'ab' (Apache Bench) for load testing${NC}"
    else
        echo -e "${RED}✗ No load testing tool found${NC}"
        echo ""
        echo "Install one of the following:"
        echo "  - hey:  go install github.com/rakyll/hey@latest"
        echo "  - ab:   sudo apt-get install apache2-utils  (or brew install ab)"
        exit 1
    fi
}

# Get JWT token
get_token() {
    echo -e "${BLUE}Obtaining JWT token...${NC}"

    TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/jtoye-dev/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=password" \
        -d "client_id=core-api" \
        -d "username=$TEST_USER" \
        -d "password=$TEST_PASSWORD" | \
        jq -r '.access_token')

    if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
        echo -e "${RED}✗ Failed to obtain JWT token${NC}"
        exit 1
    fi

    echo -e "${GREEN}✓ Token obtained${NC}"
}

# D-06 (Phase 37-04): refuse to load-test a refusal. Strict scoping is ON by default, so an
# ungranted TEST_USER reads empty lists and gets 403 on every write. Ask the server what this
# user may do; POST /shops (Test 2) needs a tenant-wide GROUP_ADMIN.
require_access() {
    local me code body admin shops
    me=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $TOKEN" \
        "$API_BASE_URL/api/v1/staff/me") || me=$'\n000'
    code="${me##*$'\n'}"; body="${me%$'\n'*}"
    if [ "$code" != "200" ]; then
        echo -e "${RED}✗ GET /api/v1/staff/me returned $code for $TEST_USER — cannot tell what it may do${NC}"
        exit 1
    fi
    admin=$(jq -r '.groupAdmin' <<< "$body" 2>/dev/null || echo "unknown")
    shops=$(jq -r '(.grantedShopIds // []) | length' <<< "$body" 2>/dev/null || echo "0")
    if [ "$admin" != "true" ]; then
        echo -e "${RED}✗ $TEST_USER is not a tenant-wide GROUP_ADMIN (grantedShops=$shops).${NC}"
        echo "  After D-06 (strict scoping ON) an ungranted user has no access, and Test 2"
        echo "  (POST /shops) needs GROUP_ADMIN. Grant $TEST_USER explicitly on the Staff page,"
        echo "  or set TEST_USER to a user that holds the grant, then re-run."
        exit 1
    fi
    echo -e "${GREEN}✓ $TEST_USER is a GROUP_ADMIN (D-06 explicit access)${NC}"
}

# Test GET /shops (read-heavy endpoint)
test_get_shops() {
    echo ""
    echo -e "${YELLOW}=== Test 1: GET /shops (Read-heavy) ===${NC}"
    echo ""

    if [ "$LOAD_TOOL" = "hey" ]; then
        hey -n $TOTAL_REQUESTS -c $CONCURRENT_USERS \
            -H "Authorization: Bearer $TOKEN" \
            "$API_BASE_URL/shops"
    else
        ab -n $TOTAL_REQUESTS -c $CONCURRENT_USERS \
            -H "Authorization: Bearer $TOKEN" \
            "$API_BASE_URL/shops"
    fi
}

# Test POST /shops (write-heavy endpoint)
test_post_shops() {
    echo ""
    echo -e "${YELLOW}=== Test 2: POST /shops (Write-heavy) ===${NC}"
    echo ""

    # Create temporary file with POST data
    cat > /tmp/shop.json << EOF
{
    "name": "Load Test Shop",
    "address": "123 Test Street",
    "phone": "+1234567890"
}
EOF

    if [ "$LOAD_TOOL" = "hey" ]; then
        local out
        out=$(hey -n $((TOTAL_REQUESTS / 10)) -c $((CONCURRENT_USERS / 2)) \
            -m POST \
            -H "Authorization: Bearer $TOKEN" \
            -H "Content-Type: application/json" \
            -D /tmp/shop.json \
            "$API_BASE_URL/shops")
        echo "$out"
        # D-06: a 403 here is the shop-access gate refusing TEST_USER, not write throughput.
        if grep -qE '^[[:space:]]*\[403\]' <<< "$out"; then
            rm -f /tmp/shop.json
            echo -e "${RED}✗ POST /shops answered 403: $TEST_USER was refused by the shop-access gate.${NC}"
            echo "  After D-06 the user needs an explicit GROUP_ADMIN grant (Staff page). The numbers above"
            echo "  measure a refusal, not a write path."
            exit 1
        fi
    else
        echo -e "${YELLOW}Note: ab doesn't support POST with body easily. Skipping write test.${NC}"
    fi

    rm -f /tmp/shop.json
}

# Test GET /products (paginated endpoint)
test_get_products() {
    echo ""
    echo -e "${YELLOW}=== Test 3: GET /products (Paginated) ===${NC}"
    echo ""

    if [ "$LOAD_TOOL" = "hey" ]; then
        hey -n $TOTAL_REQUESTS -c $CONCURRENT_USERS \
            -H "Authorization: Bearer $TOKEN" \
            "$API_BASE_URL/products?page=0&size=20"
    else
        ab -n $TOTAL_REQUESTS -c $CONCURRENT_USERS \
            -H "Authorization: Bearer $TOKEN" \
            "$API_BASE_URL/products?page=0&size=20"
    fi
}

# Test GET /actuator/health (health check endpoint)
test_health_check() {
    echo ""
    echo -e "${YELLOW}=== Test 4: GET /actuator/health (No auth) ===${NC}"
    echo ""

    if [ "$LOAD_TOOL" = "hey" ]; then
        hey -n $((TOTAL_REQUESTS * 2)) -c $((CONCURRENT_USERS * 2)) \
            "$API_BASE_URL/actuator/health"
    else
        ab -n $((TOTAL_REQUESTS * 2)) -c $((CONCURRENT_USERS * 2)) \
            "$API_BASE_URL/actuator/health"
    fi
}

# Summary and recommendations
print_summary() {
    echo ""
    echo -e "${GREEN}=== Load Testing Complete ===${NC}"
    echo ""
    echo "Next steps:"
    echo "  1. Review response times (P50, P95, P99)"
    echo "  2. Check error rates (should be 0%)"
    echo "  3. Monitor resource usage (CPU, memory, connections)"
    echo "  4. Compare with Prometheus metrics"
    echo ""
    echo "Performance targets:"
    echo "  - P95 latency: < 200ms (read operations)"
    echo "  - P95 latency: < 500ms (write operations)"
    echo "  - Error rate: 0%"
    echo "  - Throughput: > 100 req/sec per instance"
    echo ""
    echo "If issues found:"
    echo "  - Check database connection pool (max: 10)"
    echo "  - Review slow query log"
    echo "  - Monitor Grafana dashboards"
    echo "  - Check application logs"
}

# Main execution
main() {
    check_tools
    get_token
    require_access

    # Run tests
    test_health_check
    test_get_shops
    test_get_products
    test_post_shops

    print_summary
}

# Check if running in dry-run mode
if [ "${1:-}" = "--dry-run" ]; then
    echo "Dry-run mode - no tests executed"
    check_tools
    echo ""
    echo "To run actual load tests:"
    echo "  $0"
    exit 0
fi

if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    cat << EOF
JToye OaaS Load Testing Script

Usage:
    $0                      # Run all load tests
    $0 --dry-run            # Check tools without running tests
    $0 --help               # Show this help

Environment Variables:
    API_BASE_URL            API base URL (default: http://localhost:9090)
    KEYCLOAK_URL            Keycloak URL (default: http://localhost:8085)
    CONCURRENT_USERS        Concurrent users (default: 10)
    TOTAL_REQUESTS          Total requests (default: 1000)
    TEST_USER               Test user (default: tenant-a-user)
    TEST_PASSWORD           Test password (default: value of $KC_SEED_USER_PASSWORD)

Examples:
    # Light load test
    CONCURRENT_USERS=5 TOTAL_REQUESTS=500 $0

    # Heavy load test
    CONCURRENT_USERS=50 TOTAL_REQUESTS=5000 $0

    # Stress test
    CONCURRENT_USERS=100 TOTAL_REQUESTS=10000 $0

Install load testing tools:
    # hey (recommended)
    go install github.com/rakyll/hey@latest

    # Apache Bench
    sudo apt-get install apache2-utils  # Ubuntu/Debian
    brew install ab                      # macOS

EOF
    exit 0
fi

main
