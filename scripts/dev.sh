#!/usr/bin/env bash
set -euo pipefail

# Dev launcher for J'Toye OaaS (DB + edge-go + core-java)
# - Prefers Docker Compose infra if available/working
# - Falls back to a standalone Postgres container on host 5433 if Compose networking fails
# - Starts edge-go
# - Starts core-java via ./gradlew if wrapper exists; otherwise tries to generate wrapper (local Gradle or Dockerized Gradle)
# - Starts Keycloak; if Docker bridge networking fails, uses host-network fallback on port 8081

ROOT_DIR="$(cd "$(dirname "$0")"/.. && pwd)"
cd "$ROOT_DIR"

echo "[dev] Project root: $ROOT_DIR"

have_cmd() { command -v "$1" >/dev/null 2>&1; }

# --- Start Postgres (Compose preferred) ---
start_infra() {
  if have_cmd docker && have_cmd docker compose; then
    echo "[dev] Attempting to start infra via Docker Compose..."
    pushd infra >/dev/null
    # Try compose up in a subshell so we can catch failures cleanly
    if docker compose up -d; then
      echo "[dev] Compose started (postgres/keycloak)."
      export DB_HOST=localhost
      # Our compose maps Postgres default to 5432 (or 5433 if adjusted). Probe to decide port.
      if nc -z 127.0.0.1 5432 2>/dev/null; then
        export DB_PORT=5432
      else
        export DB_PORT=5433
      fi
      popd >/dev/null
      return 0
    else
      echo "[dev] Compose failed (likely iptables/network). Falling back to standalone Postgres on 5433..."
      popd >/dev/null
    fi
  else
    echo "[dev] Docker Compose not available; will use standalone Postgres if Docker exists."
  fi

  if have_cmd docker; then
    # Start or create a standalone postgres on 5433
    if docker ps -a --format '{{.Names}}' | grep -q '^jtoye-postgres$'; then
      echo "[dev] Starting existing container 'jtoye-postgres' on 5433..."
      docker start jtoye-postgres >/dev/null || true
    else
      echo "[dev] Creating container 'jtoye-postgres' on host 5433..."
      docker run -d --name jtoye-postgres \
        -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
        -p 5433:5432 \
        -v "$ROOT_DIR/infra/db/init":/docker-entrypoint-initdb.d \
        postgres:15 >/dev/null
    fi
    export DB_HOST=localhost
    export DB_PORT=5433
  else
    echo "[dev] Docker is not available. Please start a local Postgres manually and set DB_HOST/DB_PORT."
  fi
}

# --- Start edge-go ---
start_edge() {
  echo "[dev] Starting edge-go (port 8090)..."
  pushd edge-go >/dev/null
  if have_cmd go; then
    go mod tidy >/dev/null 2>&1 || true
    # If an old instance is already listening, skip new start
    if nc -z 127.0.0.1 8090 2>/dev/null; then
      echo "[dev] edge-go already running on 8090."
    else
      nohup go run ./cmd/edge >/tmp/edge-go.log 2>&1 &
      sleep 1
      if nc -z 127.0.0.1 8090 2>/dev/null; then
        echo "[dev] edge-go started. Health: http://localhost:8090/health"
      else
        echo "[dev] edge-go failed to start (check /tmp/edge-go.log)."
      fi
    fi
  else
    echo "[dev] Go toolchain is not installed. Skipping edge-go."
  fi
  popd >/dev/null
}

# --- Start core-java ---
start_core() {
  echo "[dev] Starting core-java (port 8080) with DB at ${DB_HOST:-?}:${DB_PORT:-?}..."
  # If already running, skip
  if nc -z 127.0.0.1 8080 2>/dev/null; then
    echo "[dev] core-java already listening on 8080."
    return 0
  fi

  if [ -x ./gradlew ]; then
    DB_HOST=${DB_HOST:-localhost} DB_PORT=${DB_PORT:-5432} nohup ./gradlew :core-java:bootRun >/tmp/core-java.log 2>&1 &
    sleep 2
  elif have_cmd gradle; then
    echo "[dev] Generating Gradle wrapper..."
    gradle wrapper >/dev/null
    DB_HOST=${DB_HOST:-localhost} DB_PORT=${DB_PORT:-5432} nohup ./gradlew :core-java:bootRun >/tmp/core-java.log 2>&1 &
    sleep 2
  else
    echo "[dev] Local Gradle not found. Attempting to generate wrapper using Docker (gradle:8.10.2-jdk21)..."
    if have_cmd docker; then
      docker run --rm -v "$ROOT_DIR":/home/gradle/project -w /home/gradle/project gradle:8.10.2-jdk21 gradle wrapper >/dev/null 2>&1 || true
      if [ -x ./gradlew ]; then
        DB_HOST=${DB_HOST:-localhost} DB_PORT=${DB_PORT:-5432} nohup ./gradlew :core-java:bootRun >/tmp/core-java.log 2>&1 &
        sleep 2
      else
        cat <<EOF
[dev] Failed to generate Gradle wrapper via Docker.
      Please install Gradle once (e.g., SDKMAN) and run:
      gradle wrapper
      Then re-run: DB_HOST=${DB_HOST:-localhost} DB_PORT=${DB_PORT:-5432} ./gradlew :core-java:bootRun
EOF
        return 0
      fi
    else
      cat <<EOF
[dev] Gradle is not installed and Gradle wrapper is missing, and Docker is unavailable to generate it.
      Please install Gradle once (e.g., via SDKMAN: sdk install gradle 8.10.2), then run:
      gradle wrapper
      DB_HOST=${DB_HOST:-localhost} DB_PORT=${DB_PORT:-5432} ./gradlew :core-java:bootRun
EOF
      return 0
    fi
  fi

  if curl -fsS http://localhost:8080/health >/dev/null; then
    echo "[dev] core-java started. Health: http://localhost:8080/health"
  else
    echo "[dev] core-java did not respond yet. Check /tmp/core-java.log"
  fi
}

# --- Start Keycloak (with host-network fallback) ---
start_keycloak() {
  if ! have_cmd docker; then
    echo "[dev] Docker not available; cannot start Keycloak."
    return 0
  fi
  echo "[dev] Ensuring Keycloak is running (port 8081)..."
  # If already reachable, skip
  if nc -z 127.0.0.1 8081 2>/dev/null; then
    echo "[dev] Keycloak already reachable on 8081."
    return 0
  fi
  # Try via docker compose first (bridge network)
  if have_cmd docker && have_cmd docker compose; then
    pushd infra >/dev/null
    if docker compose up -d keycloak; then
      popd >/dev/null
      echo "[dev] Keycloak started via Compose."
      return 0
    else
      echo "[dev] docker compose keycloak failed (networking?). Trying host-network override..."
      # Try host-network override compose file
      if docker compose -f docker-compose.hostnet.yml up -d; then
        popd >/dev/null
        echo "[dev] Keycloak started with host networking on http://localhost:8081"
        return 0
      fi
      popd >/dev/null
    fi
  fi
  # Fallback: run Keycloak directly with host networking using docker run
  echo "[dev] Falling back to 'docker run' with host network for Keycloak..."
  docker rm -f jtoye-keycloak-host >/dev/null 2>&1 || true
  docker run -d --name jtoye-keycloak-host \
    --network host \
    -e KEYCLOAK_ADMIN=${KC_ADMIN:-admin} \
    -e KEYCLOAK_ADMIN_PASSWORD=${KC_ADMIN_PASSWORD:-admin123} \
    -v "$ROOT_DIR/infra/keycloak/realm-export.json":/opt/keycloak/data/import/realm-export.json:ro \
    quay.io/keycloak/keycloak:24.0.5 start-dev --import-realm --http-port=8081 >/dev/null 2>&1 || true
  if nc -z 127.0.0.1 8081 2>/dev/null; then
    echo "[dev] Keycloak started on http://localhost:8081"
  else
    echo "[dev] Keycloak did not become reachable on 8081. Check Docker/network setup."
  fi
}

# --- Health summary ---
health_summary() {
  echo "\n[dev] Health summary:"
  (curl -fsS http://localhost:8090/health && echo) || echo '{"edge-go":"DOWN"}'
  (curl -fsS http://localhost:8080/health && echo) || echo 'core-java: DOWN'
  echo "Postgres TCP: ${DB_HOST:-localhost}:${DB_PORT:-unknown}"
}

start_infra
start_edge
start_core
start_keycloak
health_summary

echo "\n[dev] Done. Press Ctrl+C to stop foreground tails (services run in background)."
