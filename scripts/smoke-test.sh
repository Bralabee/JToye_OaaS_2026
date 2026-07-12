#!/bin/bash
# Smoke tests for J'Toye OaaS deployment
# Validates that the application is functional after deployment

set -e

API_URL="${1:-http://localhost:9090}"
TIMEOUT=30

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

set +e

echo -e "${YELLOW}=== J'Toye OaaS Smoke Test Suite ===${NC}"
echo "API URL: $API_URL"
echo ""

# Function to test endpoint
test_endpoint() {
    local name=$1
    local url=$2
    local expected_code=${3:-200}
    local method=${4:-GET}

    echo -n "Testing $name... "
    
    response=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" \
        --max-time $TIMEOUT \
        "$url" 2>/dev/null || echo "000")

    if [ "$response" = "$expected_code" ]; then
        echo -e "${GREEN}✓ PASS${NC} (HTTP $response)"
        return 0
    else
        echo -e "${RED}✗ FAIL${NC} (Expected HTTP $expected_code, got $response)"
        return 1
    fi
}

# Test counter
TESTS_PASSED=0
TESTS_FAILED=0

# Test 1: Health endpoint
if test_endpoint "Health Endpoint" "$API_URL/health"; then
    ((TESTS_PASSED++))
else
    ((TESTS_FAILED++))
fi

# Test 2: Actuator health
if test_endpoint "Actuator Health" "$API_URL/actuator/health"; then
    ((TESTS_PASSED++))
else
    ((TESTS_FAILED++))
fi

# Test 3: Actuator info
if test_endpoint "Actuator Info" "$API_URL/actuator/info"; then
    ((TESTS_PASSED++))
else
    ((TESTS_FAILED++))
fi

# Test 4: Kubernetes liveness probe (kubelet hits this unauthenticated — must be 200)
if test_endpoint "Liveness Probe" "$API_URL/actuator/health/liveness"; then
    ((TESTS_PASSED++))
else
    ((TESTS_FAILED++))
fi

# Test 5: Kubernetes readiness probe (kubelet hits this unauthenticated — must be 200)
if test_endpoint "Readiness Probe" "$API_URL/actuator/health/readiness"; then
    ((TESTS_PASSED++))
else
    ((TESTS_FAILED++))
fi

# Swagger / API docs — environment-conditional.
#   EXPECT_SWAGGER=true  (staging): Swagger is deliberately enabled, so assert it
#                        is reachable (/swagger-ui.html -> 302, /v3/api-docs -> 200).
#   EXPECT_SWAGGER unset/false (prod): Swagger is disabled (SWAGGER_ENABLED:false),
#                        so assert BOTH are NOT publicly exposed — the status must
#                        NOT be a 2xx/3xx (401 or 404 are both acceptable). This is
#                        what stops a healthy prod release from failing smoke and
#                        auto-rolling-back a good deploy.
EXPECT_SWAGGER="${EXPECT_SWAGGER:-false}"
if [ "$EXPECT_SWAGGER" = "true" ]; then
    if test_endpoint "Swagger UI (expected reachable)" "$API_URL/swagger-ui.html" "302"; then
        ((TESTS_PASSED++))
    else
        ((TESTS_FAILED++))
    fi
    if test_endpoint "API Docs (expected reachable)" "$API_URL/v3/api-docs"; then
        ((TESTS_PASSED++))
    else
        ((TESTS_FAILED++))
    fi
else
    for pair in "Swagger UI:/swagger-ui.html" "API Docs:/v3/api-docs"; do
        sw_name="${pair%%:*}"
        sw_path="${pair#*:}"
        echo -n "Testing $sw_name (expected NOT publicly exposed)... "
        sw_code=$(curl -s -o /dev/null -w "%{http_code}" -X GET \
            --max-time $TIMEOUT \
            "$API_URL$sw_path" 2>/dev/null || echo "000")
        # Pass when NOT a success/redirect (2xx/3xx). 401 (secured) or 404
        # (disabled/not-mapped) both prove Swagger is not publicly served.
        if [ "$sw_code" -ge 200 ] 2>/dev/null && [ "$sw_code" -lt 400 ] 2>/dev/null; then
            echo -e "${RED}✗ FAIL${NC} (publicly exposed: HTTP $sw_code)"
            ((TESTS_FAILED++))
        else
            echo -e "${GREEN}✓ PASS${NC} (not exposed: HTTP $sw_code)"
            ((TESTS_PASSED++))
        fi
    done
fi

# Test 6: Protected endpoint (should return 401)
if test_endpoint "Protected Endpoint (Auth Check)" "$API_URL/shops" "401"; then
    ((TESTS_PASSED++))
else
    ((TESTS_FAILED++))
fi

# Test 7: Invalid endpoint (should return 401 or 404)
echo -n "Testing Invalid Endpoint... "
INVALID_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X GET \
    --max-time $TIMEOUT \
    "$API_URL/nonexistent" 2>/dev/null || echo "000")

if [ "$INVALID_RESPONSE" = "404" ] || [ "$INVALID_RESPONSE" = "401" ]; then
    echo -e "${GREEN}✓ PASS${NC} (HTTP $INVALID_RESPONSE)"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗ FAIL${NC} (Expected HTTP 404/401, got $INVALID_RESPONSE)"
    ((TESTS_FAILED++))
fi

# Test 8: CORS headers (OPTIONS request)
echo -n "Testing CORS Support... "
CORS_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X OPTIONS \
    -H "Origin: http://localhost:3000" \
    -H "Access-Control-Request-Method: POST" \
    --max-time $TIMEOUT \
    "$API_URL/shops" 2>/dev/null || echo "000")

if [ "$CORS_RESPONSE" = "200" ] || [ "$CORS_RESPONSE" = "204" ]; then
    echo -e "${GREEN}✓ PASS${NC} (HTTP $CORS_RESPONSE)"
    ((TESTS_PASSED++))
else
    echo -e "${RED}✗ FAIL${NC} (Expected HTTP 200/204, got $CORS_RESPONSE)"
    ((TESTS_FAILED++))
fi

# Summary
echo ""
echo -e "${YELLOW}=== Test Summary ===${NC}"
echo -e "Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Failed: ${RED}$TESTS_FAILED${NC}"
TOTAL=$((TESTS_PASSED + TESTS_FAILED))
echo "Total: $TOTAL"

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "\n${GREEN}✓ All smoke tests passed!${NC}"
    exit 0
else
    echo -e "\n${RED}✗ Some tests failed${NC}"
    exit 1
fi
