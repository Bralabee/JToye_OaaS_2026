#!/usr/bin/env bash
# Smoke test for STOMP broker relay with 2 core-java replicas.
# Validates that RabbitMQ STOMP connections are established and
# the system is ready for cross-replica broadcasting.
#
# Usage: ./scripts/smoke-test-stomp-relay.sh [edge-url] [rabbitmq-api-url]
# Prerequisites: docker compose stack running with
#   STOMP_BROKER_MODE=relay docker compose -f docker-compose.full-stack.yml up --scale core-java=2

set -euo pipefail

EDGE_URL="${1:-http://localhost:8089}"
RABBITMQ_API="${2:-http://localhost:15672}"
TIMEOUT=30
TESTS_PASSED=0
TESTS_FAILED=0

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

set +e

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
        echo -e "${GREEN}PASS${NC} (HTTP $response)"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        echo -e "${RED}FAIL${NC} (Expected HTTP $expected_code, got $response)"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

echo -e "${YELLOW}=== STOMP Broker Relay Smoke Test ===${NC}"
echo "Edge URL: $EDGE_URL"
echo "RabbitMQ API: $RABBITMQ_API"
echo ""

# 1. Health checks
echo "--- Health Checks ---"
test_endpoint "Edge gateway health" "$EDGE_URL/health"
test_endpoint "RabbitMQ management API" "$RABBITMQ_API/api/overview" 200

# 2. Check STOMP plugin is enabled via port 61613
echo ""
echo "--- STOMP Plugin Check ---"
echo -n "Checking STOMP port 61613... "
if nc -z localhost 61613 2>/dev/null; then
    echo -e "${GREEN}PASS${NC} (port 61613 reachable)"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    # Fallback: check RabbitMQ listeners via management API
    LISTENERS=$(curl -sf -u "${RABBITMQ_DEFAULT_USER:-guest}:${RABBITMQ_DEFAULT_PASS:-guest}" \
        "$RABBITMQ_API/api/overview" 2>/dev/null | \
        python3 -c "import sys,json; d=json.load(sys.stdin); print([l.get('protocol','') for l in d.get('listeners',[])])" 2>/dev/null || echo "")
    if echo "$LISTENERS" | grep -q "stomp"; then
        echo -e "${GREEN}PASS${NC} (STOMP listener found via API)"
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        echo -e "${RED}FAIL${NC} (port 61613 not reachable and no STOMP listener in API)"
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
fi

# 3. Check STOMP connections (system connections from core-java replicas)
echo ""
echo "--- STOMP Relay Connections ---"
echo -n "Checking STOMP connections (expect >= 2 for 2 replicas)... "
STOMP_CONNS=$(curl -sf -u "${RABBITMQ_DEFAULT_USER:-guest}:${RABBITMQ_DEFAULT_PASS:-guest}" \
    "$RABBITMQ_API/api/connections" 2>/dev/null | \
    python3 -c "
import sys, json
conns = json.load(sys.stdin)
stomp_count = len([c for c in conns if 'stomp' in c.get('protocol', '').lower()])
print(stomp_count)
" 2>/dev/null || echo "0")

if [ "$STOMP_CONNS" -ge 2 ] 2>/dev/null; then
    echo -e "${GREEN}PASS${NC} ($STOMP_CONNS STOMP connections -- expected >= 2 for 2 replicas)"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    echo -e "${YELLOW}WARN${NC} ($STOMP_CONNS STOMP connections -- expected >= 2; connections may still be establishing)"
    TESTS_FAILED=$((TESTS_FAILED + 1))
fi

# 4. Check core-java replica count via docker
echo ""
echo "--- Replica Count ---"
echo -n "Checking core-java container count... "
REPLICA_COUNT=$(docker compose -f docker-compose.full-stack.yml ps --format '{{.Name}}' 2>/dev/null | grep -c "core-java" || echo "0")
if [ "$REPLICA_COUNT" -ge 2 ] 2>/dev/null; then
    echo -e "${GREEN}PASS${NC} ($REPLICA_COUNT core-java replicas running)"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    echo -e "${YELLOW}WARN${NC} ($REPLICA_COUNT core-java replicas -- expected >= 2; run with --scale core-java=2)"
    TESTS_FAILED=$((TESTS_FAILED + 1))
fi

# 5. Verify both replicas are healthy
echo ""
echo "--- Replica Health ---"
HEALTHY_COUNT=0
for container in $(docker compose -f docker-compose.full-stack.yml ps --format '{{.Name}}' 2>/dev/null | grep "core-java"); do
    STATUS=$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || echo "unknown")
    echo -n "  $container: "
    if [ "$STATUS" = "healthy" ]; then
        echo -e "${GREEN}healthy${NC}"
        HEALTHY_COUNT=$((HEALTHY_COUNT + 1))
    else
        echo -e "${YELLOW}$STATUS${NC}"
    fi
done
echo -n "Healthy replicas check... "
if [ "$HEALTHY_COUNT" -ge 2 ] 2>/dev/null; then
    echo -e "${GREEN}PASS${NC} ($HEALTHY_COUNT healthy)"
    TESTS_PASSED=$((TESTS_PASSED + 1))
else
    echo -e "${YELLOW}WARN${NC} ($HEALTHY_COUNT healthy -- expected >= 2)"
    TESTS_FAILED=$((TESTS_FAILED + 1))
fi

# Summary
echo ""
echo -e "${YELLOW}=== Test Summary ===${NC}"
echo -e "Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Failed: ${RED}$TESTS_FAILED${NC}"
TOTAL=$((TESTS_PASSED + TESTS_FAILED))
echo "Total: $TOTAL"

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "\n${GREEN}All STOMP relay smoke tests passed!${NC}"
    exit 0
else
    echo -e "\n${RED}Some tests failed -- check output above${NC}"
    exit 1
fi
