#!/bin/bash
# Fix Docker iptables issue - V2 (More aggressive)
# Run this script with: sudo bash fix-docker-iptables-v2.sh

set -e

echo "=== Docker iptables Fix Script V2 ==="
echo ""

echo "Step 1: Stop Docker completely (socket + service)..."
systemctl stop docker.socket
systemctl stop docker

echo "Step 2: Verify Docker is stopped..."
sleep 2
systemctl status docker --no-pager | head -5 || true

echo ""
echo "Step 3: Switch to iptables-legacy..."
update-alternatives --set iptables /usr/sbin/iptables-legacy
update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy

echo ""
echo "Step 4: Verify iptables switched..."
iptables --version

echo ""
echo "Step 5: Clean ALL Docker iptables rules (both nf_tables and legacy)..."
# Try with nft backend first
iptables -t nat -F 2>/dev/null || true
iptables -t filter -F 2>/dev/null || true
iptables -t nat -X 2>/dev/null || true
iptables -t filter -X 2>/dev/null || true

echo ""
echo "Step 6: Create Docker chains with iptables-legacy..."
iptables -t filter -N DOCKER 2>/dev/null || echo "DOCKER chain exists"
iptables -t filter -N DOCKER-ISOLATION-STAGE-1 2>/dev/null || echo "DOCKER-ISOLATION-STAGE-1 exists"
iptables -t filter -N DOCKER-ISOLATION-STAGE-2 2>/dev/null || echo "DOCKER-ISOLATION-STAGE-2 exists"
iptables -t filter -A DOCKER-ISOLATION-STAGE-1 -j RETURN 2>/dev/null || true
iptables -t filter -A DOCKER-ISOLATION-STAGE-2 -j RETURN 2>/dev/null || true
iptables -t nat -N DOCKER 2>/dev/null || echo "DOCKER nat chain exists"

echo ""
echo "Step 7: List created chains..."
iptables -t filter -L | grep -E "^Chain DOCKER"

echo ""
echo "Step 8: Start Docker (socket first, then service)..."
systemctl start docker.socket
systemctl start docker

echo ""
echo "Step 9: Wait for Docker to fully start..."
sleep 3

echo ""
echo "Step 10: Verify Docker is running and check version..."
systemctl status docker --no-pager | head -5
docker version 2>&1 | head -10

echo ""
echo "=== Fix Complete ==="
echo ""
echo "Now test with:"
echo "  docker network create test-network"
echo "  docker network rm test-network"
