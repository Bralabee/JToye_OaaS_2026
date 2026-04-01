#!/bin/bash
# Fix Testcontainers compatibility with Docker Engine 29+
# Testcontainers 1.21.x uses Docker API v1.32, but Docker 29+ requires >= 1.40
# This script configures Docker to accept older API clients.
#
# Usage: sudo ./scripts/fix-testcontainers-docker.sh
# After running: ./gradlew test -PincludeIntegration

set -euo pipefail

DAEMON_JSON="/etc/docker/daemon.json"

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: This script must be run as root (sudo)"
    exit 1
fi

echo "Current Docker daemon config:"
cat "$DAEMON_JSON" 2>/dev/null || echo "{}"
echo ""

# Add minimum API version using python3 (available on most systems)
python3 -c "
import json, sys

try:
    with open('$DAEMON_JSON', 'r') as f:
        config = json.load(f)
except FileNotFoundError:
    config = {}

config['default-minimum-api-version'] = '1.24'

with open('$DAEMON_JSON', 'w') as f:
    json.dump(config, f, indent=2)
    f.write('\n')

print('Updated daemon.json:')
print(json.dumps(config, indent=2))
"

echo ""
echo "Restarting Docker daemon..."
systemctl restart docker

echo ""
echo "Docker restarted. Verifying..."
docker version --format 'Client API: {{.Client.APIVersion}}, Server API: {{.Server.APIVersion}}'
echo ""
echo "Done. You can now run: ./gradlew test -PincludeIntegration"
