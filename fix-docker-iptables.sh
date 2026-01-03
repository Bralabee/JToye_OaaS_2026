#!/bin/bash
# Fix Docker iptables issue
# Run this script with: sudo bash fix-docker-iptables.sh

set -e

echo "=== Docker iptables Fix Script ==="
echo ""

echo "Step 1: Stop Docker daemon..."
systemctl stop docker

echo "Step 2: Switch to iptables-legacy..."
update-alternatives --set iptables /usr/sbin/iptables-legacy
update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy

echo "Step 3: Clean up existing Docker iptables rules..."
iptables -t nat -F || true
iptables -t nat -X || true
iptables -t filter -F DOCKER || true
iptables -t filter -F DOCKER-ISOLATION-STAGE-1 || true
iptables -t filter -F DOCKER-ISOLATION-STAGE-2 || true
iptables -t filter -X DOCKER || true
iptables -t filter -X DOCKER-ISOLATION-STAGE-1 || true
iptables -t filter -X DOCKER-ISOLATION-STAGE-2 || true

echo "Step 4: Create Docker iptables chains..."
iptables -t filter -N DOCKER || true
iptables -t filter -N DOCKER-ISOLATION-STAGE-1 || true
iptables -t filter -N DOCKER-ISOLATION-STAGE-2 || true
iptables -t filter -A DOCKER-ISOLATION-STAGE-1 -j RETURN || true
iptables -t filter -A DOCKER-ISOLATION-STAGE-2 -j RETURN || true

echo "Step 5: Start Docker daemon..."
systemctl start docker

echo "Step 6: Verify Docker is running..."
systemctl status docker --no-pager | head -5

echo ""
echo "=== Fix Complete ==="
echo "Verify with: docker network create test-network && docker network rm test-network"
