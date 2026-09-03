#!/bin/bash
# Start the J'Toye OaaS HYBRID development runtime.
#
# This is NOT the canonical full-stack Compose runtime and it never reads
# docker-compose.full-stack.yml. It starts:
#   1. infra/docker-compose.yml  — Postgres + Keycloak ONLY, in Docker.
#                                  That file reads infra/.env, NOT the repo-root .env,
#                                  and declares seven ${VAR:?} guards; without infra/.env
#                                  step 1 fails. The preflight below validates the
#                                  REPO-ROOT .env, so a green preflight does not prove
#                                  the compose file can render.
#   2. ./gradlew :core-java:bootRun  — as a HOST process.
#   3. npm run dev (frontend)        — as a HOST process.
# Teardown: scripts/stop-dev.sh (its "HYBRID" arm pairs with this script).
# Never run alongside docker-compose.full-stack.yml: both bind host ports 5433 and 8085.
#
# FLAGS: this script has none of its own. Every argument is forwarded to
# scripts/verify-env.sh (see below) and the stack is then started REGARDLESS, so
# `start-dev.sh --help` STARTS SERVICES. Adding real flag handling is tracked separately.

set -e

echo "🚀 Starting J'Toye OaaS Development Environment"
echo "================================================"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

cd "$(dirname "$0")/.."

# Preflight: fail loud on missing/weak credentials BEFORE bringing up any
# container (issue #80). Validates ./.env by default; pass an env-file path or
# --with-stack through as arguments. See scripts/verify-env.sh for the contract.
echo -e "\n${YELLOW}Preflight: verifying environment (scripts/verify-env.sh)${NC}"
bash scripts/verify-env.sh "$@" || { echo 'verify-env failed — fix the named variable(s) in your .env before starting the stack'; exit 1; }

# Step 1: Start Infrastructure
echo -e "\n${YELLOW}Step 1: Starting Infrastructure (PostgreSQL, Keycloak)${NC}"
cd infra
docker compose up -d
cd ..

echo "Waiting for infrastructure to be ready..."

# Poll Keycloak directly; no need for a blanket sleep.
# Bounded at 120 attempts * 2s = 4 minutes.
echo "Checking Keycloak..."
attempt=0
until curl -s http://localhost:8085/realms/jtoye-dev/.well-known/openid-configuration > /dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [[ $attempt -gt 120 ]]; then
    echo -e "${YELLOW}✗ Keycloak did not become ready in 4 minutes. Check: docker compose logs keycloak${NC}"
    exit 1
  fi
  echo "  Waiting for Keycloak... (attempt $attempt/120)"
  sleep 2
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
attempt=0
until curl -s http://localhost:3000 > /dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [[ $attempt -gt 60 ]]; then
    echo -e "${YELLOW}✗ Frontend did not become ready in 2 minutes. Check: tail -f logs/frontend.log${NC}"
    exit 1
  fi
  sleep 2
done
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
echo "  Password: value of \$KC_SEED_USER_PASSWORD from your .env"
echo ""
echo "Logs:"
echo "  Backend:  logs/backend.log"
echo "  Frontend: logs/frontend.log"
echo ""
echo "To stop all services:"
echo "  ./scripts/stop-dev.sh"
echo ""
