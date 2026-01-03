#!/bin/bash
# Stop J'Toye OaaS Development Environment

echo "🛑 Stopping J'Toye OaaS Development Environment"
echo "================================================"

# Stop frontend (more aggressive)
echo "Stopping frontend..."
pkill -9 -f "npm run dev" || true
pkill -9 -f "node.*next.*dev" || true
pkill -9 -f "next-server" || true

# Stop backend
echo "Stopping backend..."
pkill -9 -f "gradlew" || true
pkill -9 -f "java.*CoreApplication" || true

# Wait a moment for processes to die
sleep 2

# Stop infrastructure
echo "Stopping infrastructure..."
cd infra
docker compose down
cd ..

echo ""
echo "✅ All services stopped"
