#!/bin/bash
#
# JToye OaaS Load Testing Script
# Uses Apache Bench (ab) or hey for load testing
#
# Purpose: Establish capacity limits and performance baselines
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
TEST_PASSWORD="${TEST_PASSWORD:-password123}"

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
        hey -n $((TOTAL_REQUESTS / 10)) -c $((CONCURRENT_USERS / 2)) \
            -m POST \
            -H "Authorization: Bearer $TOKEN" \
            -H "Content-Type: application/json" \
            -D /tmp/shop.json \
            "$API_BASE_URL/shops"
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
    TEST_PASSWORD           Test password (default: password123)

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
