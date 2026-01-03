#!/bin/bash
# Start J'Toye OaaS Development Environment
# This script starts all services in the correct order

set -e

echo "🚀 Starting J'Toye OaaS Development Environment"
echo "================================================"

# Colors
GREEN='\033[0.32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Step 1: Start Infrastructure
echo -e "\n${YELLOW}Step 1: Starting Infrastructure (PostgreSQL, Keycloak)${NC}"
cd infra
docker compose up -d
cd ..

echo "Waiting for infrastructure to be ready..."
sleep 15

# Check if Keycloak is up
echo "Checking Keycloak..."
until curl -s http://localhost:8085/realms/jtoye-dev/.well-known/openid-configuration > /dev/null 2>&1; do
  echo "  Waiting for Keycloak..."
  sleep 5
done
echo -e "${GREEN}✓ Keycloak is ready${NC}"

# Check if PostgreSQL is up
echo "Checking PostgreSQL..."
docker exec jtoye-postgres pg_isready -U jtoye > /dev/null 2>&1 && echo -e "${GREEN}✓ PostgreSQL is ready${NC}"

# Step 2: Start Backend
echo -e "\n${YELLOW}Step 2: Starting Backend (Spring Boot)${NC}"
./gradlew :core-java:bootRun > logs/backend.log 2>&1 &
BACKEND_PID=$!
echo "Backend started with PID: $BACKEND_PID"

# Wait for backend
echo "Waiting for backend to be ready..."
until curl -s http://localhost:9090/actuator/health > /dev/null 2>&1; do
  echo "  Waiting for backend..."
  sleep 3
done
echo -e "${GREEN}✓ Backend is ready${NC}"

# Step 3: Start Frontend
echo -e "\n${YELLOW}Step 3: Starting Frontend (Next.js)${NC}"
cd frontend
npm run dev > ../logs/frontend.log 2>&1 &
FRONTEND_PID=$!
cd ..
echo "Frontend started with PID: $FRONTEND_PID"

# Wait for frontend
echo "Waiting for frontend to be ready..."
sleep 10
echo -e "${GREEN}✓ Frontend is ready${NC}"

# Done
echo -e "\n${GREEN}================================================${NC}"
echo -e "${GREEN}✅ All services started successfully!${NC}"
echo -e "${GREEN}================================================${NC}"
echo ""
echo "Access your application:"
echo "  🌐 Frontend:  http://localhost:3000"
echo "  🔌 Backend:   http://localhost:9090"
echo "  🔐 Keycloak:  http://localhost:8085"
echo ""
echo "Login credentials:"
echo "  Username: tenant-a-user"
echo "  Password: password123"
echo ""
echo "Logs:"
echo "  Backend:  logs/backend.log"
echo "  Frontend: logs/frontend.log"
echo ""
echo "To stop all services:"
echo "  ./stop-dev.sh"
echo ""
