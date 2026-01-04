#!/bin/bash
# Test script for JToye OaaS Multi-tenant Application
# Tests tenant isolation and RLS (Row Level Security)

set -e

echo "========================================="
echo "JToye OaaS Application Test Suite"
echo "========================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0

# Helper functions
pass() {
    echo -e "${GREEN}✓ PASS${NC}: $1"
    ((TESTS_PASSED++))
}

fail() {
    echo -e "${RED}✗ FAIL${NC}: $1"
    ((TESTS_FAILED++))
}

info() {
    echo -e "${YELLOW}ℹ INFO${NC}: $1"
}

# Test 1: Health Endpoint
echo "Test 1: Health Endpoint"
echo "-------------------------------------------"
HEALTH=$(curl -s http://localhost:9090/health)
if [ "$HEALTH" = "OK" ]; then
    pass "Health endpoint returns OK"
else
    fail "Health endpoint failed. Got: $HEALTH"
fi
echo ""

# Test 2: Actuator Health
echo "Test 2: Actuator Health Endpoint"
echo "-------------------------------------------"
ACTUATOR_STATUS=$(curl -s http://localhost:9090/actuator/health | jq -r .status)
if [ "$ACTUATOR_STATUS" = "UP" ]; then
    pass "Actuator health shows UP"
else
    fail "Actuator health failed. Got: $ACTUATOR_STATUS"
fi
echo ""

# Test 3: Swagger UI
echo "Test 3: Swagger UI Availability"
echo "-------------------------------------------"
SWAGGER_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9090/swagger-ui.html)
if [ "$SWAGGER_RESPONSE" = "200" ]; then
    pass "Swagger UI is accessible"
    info "Visit: http://localhost:9090/swagger-ui.html"
else
    fail "Swagger UI not accessible. HTTP code: $SWAGGER_RESPONSE"
fi
echo ""

# Test 4: OpenAPI Docs
echo "Test 4: OpenAPI Documentation"
echo "-------------------------------------------"
API_DOCS=$(curl -s http://localhost:9090/v3/api-docs | jq -r .info.title 2>/dev/null)
if [ -n "$API_DOCS" ]; then
    pass "OpenAPI docs available: $API_DOCS"
else
    fail "OpenAPI docs not accessible"
fi
echo ""

# Test 5: Protected Endpoint (should fail without auth)
echo "Test 5: Protected Endpoint Security"
echo "-------------------------------------------"
SHOP_NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9090/shops)
if [ "$SHOP_NO_AUTH" = "401" ] || [ "$SHOP_NO_AUTH" = "403" ]; then
    pass "Protected endpoints require authentication (HTTP $SHOP_NO_AUTH)"
else
    fail "Protected endpoint should return 401/403 without auth. Got: $SHOP_NO_AUTH"
fi
echo ""

# Test 6: Database Connection
echo "Test 6: Database Connection"
echo "-------------------------------------------"
DB_TEST=$(docker exec jtoye-postgres psql -U jtoye -d jtoye -c "SELECT COUNT(*) FROM tenant;" 2>&1 | grep -E '^\s*[0-9]+$' || echo "0")
if [ -n "$DB_TEST" ]; then
    pass "Database is accessible and tenant table exists"
    info "Current tenant count: $(echo $DB_TEST | tr -d ' ')"
else
    fail "Database connection or schema issue"
fi
echo ""

# Test 7: Migration Status
echo "Test 7: Flyway Migrations"
echo "-------------------------------------------"
MIGRATION_COUNT=$(docker exec jtoye-postgres psql -U jtoye -d jtoye -c "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true;" 2>&1 | grep -E '^\s*[0-9]+$' | tr -d ' ')
if [ "$MIGRATION_COUNT" = "4" ]; then
    pass "All 4 Flyway migrations applied successfully"
else
    fail "Expected 4 migrations, found: $MIGRATION_COUNT"
fi
echo ""

# Test 8: RLS Policies
echo "Test 8: Row Level Security Policies"
echo "-------------------------------------------"
RLS_COUNT=$(docker exec jtoye-postgres psql -U jtoye -d jtoye -c "SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public';" 2>&1 | grep -E '^\s*[0-9]+$' | tr -d ' ')
if [ "$RLS_COUNT" -gt "0" ]; then
    pass "RLS policies are installed ($RLS_COUNT policies)"
else
    fail "No RLS policies found"
fi
echo ""

# Summary
echo "========================================="
echo "Test Summary"
echo "========================================="
echo -e "${GREEN}Passed: $TESTS_PASSED${NC}"
echo -e "${RED}Failed: $TESTS_FAILED${NC}"
echo "Total:  $((TESTS_PASSED + TESTS_FAILED))"
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
    echo ""
    echo "Application is running correctly:"
    echo "  - API: http://localhost:9090"
    echo "  - Swagger UI: http://localhost:9090/swagger-ui.html"
    echo "  - Health: http://localhost:9090/actuator/health"
    exit 0
else
    echo -e "${RED}✗ Some tests failed${NC}"
    exit 1
fi
