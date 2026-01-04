#!/bin/bash
# This script ensures that Docker bridge networking works on systems where
# nftables/iptables-nft prevents inter-container communication.

echo "Applying nftables fix for Docker bridge networking..."

# Add ACCEPT rule to DOCKER-USER chain if it exists
if sudo nft list chain inet filter DOCKER-USER > /dev/null 2>&1; then
    sudo nft add rule inet filter DOCKER-USER accept
    echo "✅ Applied 'accept' rule to nftables DOCKER-USER chain."
else
    # Fallback to iptables if nft chain doesn't exist
    sudo iptables -I DOCKER-USER -j ACCEPT
    echo "✅ Applied 'ACCEPT' rule to iptables DOCKER-USER chain."
fi

# Ensure IP forwarding is enabled
sudo sysctl -w net.ipv4.ip_forward=1

echo "Verification:"
docker run --rm alpine ping -c 1 8.8.8.8 > /dev/null && echo "  - Internet access: OK" || echo "  - Internet access: FAILED"
echo "Fix applied successfully."
