#!/bin/bash
# Fix Docker networking - both host-to-container and container-to-container
# This script addresses the iptables/nf_tables incompatibility issue

set -e

echo "🔧 Fixing Docker networking issues..."
echo ""

# Check if running as root
if [ "$EUID" -ne 0 ]; then
   echo "❌ Please run as root or with sudo"
   exit 1
fi

# Step 1: Stop Docker
echo "1️⃣  Stopping Docker..."
systemctl stop docker.socket
systemctl stop docker
sleep 2

# Step 2: Switch to iptables-legacy
echo "2️⃣  Switching to iptables-legacy..."
update-alternatives --set iptables /usr/sbin/iptables-legacy
update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy

# Step 3: Flush existing rules
echo "3️⃣  Flushing existing iptables rules..."
iptables -F
iptables -t nat -F
iptables -t mangle -F
iptables -X

# Step 4: Set default policies
echo "4️⃣  Setting default policies..."
iptables -P INPUT ACCEPT
iptables -P FORWARD ACCEPT
iptables -P OUTPUT ACCEPT

# Step 5: Create required Docker chains
echo "5️⃣  Creating Docker iptables chains..."
iptables -N DOCKER 2>/dev/null || true
iptables -N DOCKER-ISOLATION-STAGE-1 2>/dev/null || true
iptables -N DOCKER-ISOLATION-STAGE-2 2>/dev/null || true
iptables -N DOCKER-USER 2>/dev/null || true

# Step 6: Add FORWARD rules for container-to-container communication
echo "6️⃣  Adding FORWARD rules for inter-container communication..."
iptables -A FORWARD -j DOCKER-USER
iptables -A FORWARD -j DOCKER-ISOLATION-STAGE-1
iptables -A FORWARD -o docker0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
iptables -A FORWARD -o docker0 -j DOCKER
iptables -A FORWARD -i docker0 ! -o docker0 -j ACCEPT
iptables -A FORWARD -i docker0 -o docker0 -j ACCEPT

# Docker isolation rules
iptables -A DOCKER-ISOLATION-STAGE-1 -i docker0 ! -o docker0 -j DOCKER-ISOLATION-STAGE-2
iptables -A DOCKER-ISOLATION-STAGE-1 -j RETURN
iptables -A DOCKER-ISOLATION-STAGE-2 -o docker0 -j DROP
iptables -A DOCKER-ISOLATION-STAGE-2 -j RETURN

# Docker user chain (allows custom rules)
iptables -A DOCKER-USER -j RETURN

# Step 7: Start Docker
echo "7️⃣  Starting Docker..."
systemctl start docker
sleep 3

# Step 8: Verify
echo "8️⃣  Verifying Docker status..."
if systemctl is-active --quiet docker; then
    echo "✅ Docker is running"
else
    echo "❌ Docker failed to start"
    exit 1
fi

# Check if Docker networks work
echo "9️⃣  Checking Docker network..."
if docker network ls > /dev/null 2>&1; then
    echo "✅ Docker networking is functional"
else
    echo "❌ Docker networking has issues"
    exit 1
fi

echo ""
echo "✅ Docker networking fix completed successfully!"
echo ""
echo "Next steps:"
echo "  1. Test with: docker compose -f docker-compose.full-stack.yml up -d"
echo "  2. Check connectivity: docker exec jtoye-core-java ping -c 2 postgres"
echo ""
