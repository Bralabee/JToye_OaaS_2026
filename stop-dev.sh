#!/bin/bash
# Stop J'Toye OaaS Development Environment

echo "🛑 Stopping J'Toye OaaS Development Environment"
echo "================================================"

# Stop frontend
echo "Stopping frontend..."
pkill -f "npm run dev" || true

# Stop backend
echo "Stopping backend..."
pkill -f "gradlew" || true

# Stop infrastructure
echo "Stopping infrastructure..."
cd infra
docker compose down
cd ..

echo ""
echo "✅ All services stopped"
