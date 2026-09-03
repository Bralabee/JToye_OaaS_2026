# Environment Setup Guide

**J'Toye OaaS - Complete Environment Configuration**

This guide explains how to configure environment variables for local development across all platforms (Windows, Linux, macOS).

---

## Table of Contents

1. [Overview](#overview)
2. [Quick Start Checklist](#quick-start-checklist)
3. [Environment Files Required](#environment-files-required)
4. [Platform-Specific Instructions](#platform-specific-instructions)
5. [Configuration Details](#configuration-details)
6. [Validation](#validation)
7. [Troubleshooting](#troubleshooting)

---

## Overview

J'Toye OaaS uses environment variables to configure:
- Database connections
- Authentication (Keycloak/OAuth2)
- Service URLs and ports
- Application secrets

**Important:** `.env` files contain secrets and are NOT committed to Git. Each developer must create their own from the provided templates.

---

## Quick Start Checklist

Before running the application, you need to create environment files:

### ✅ For Full-Stack Docker Setup (Easiest)
- [ ] `cp .env.example .env` at the repo root and fill in every `CHANGE_ME`
- [ ] `bash scripts/verify-env.sh` passes
- [ ] Skip to [Running with Docker](#running-with-docker)

> `docker-compose.full-stack.yml` declares **18 hard-required** variables in `${VAR:?}` form.
> Without a `.env` even `docker compose config` fails with
> `required variable POSTGRES_PASSWORD is missing a value`, so this is the first step and not an
> optional one.

### ✅ For Local Development (Individual Services)
- [ ] Create `frontend/.env.local` from `frontend/.env.local.example`
- [ ] Create `core-java/.env` from `core-java/.env.example`
- [ ] Create `edge-go/.env` from `edge-go/.env.example`
- [ ] Create `infra/.env` from `infra/.env.example`

---

## Local Development Modes

J'Toye OaaS supports **two local development modes**:

### Mode 1: Hybrid (Recommended for Core Java Development)
**Run core-java locally, connect to Dockerized dependencies**

- ✅ **Best for:** Backend development, debugging, hot-reload
- ✅ **Fast:** Instant code changes without Docker rebuild
- ✅ **Easy debugging:** Attach IDE debugger directly
- 📦 **Dependencies:** PostgreSQL, Keycloak, Redis, RabbitMQ run in Docker
- 🔧 **Configuration:** Use Spring profile `local` or set `DB_PORT=5433`

```bash
# Start dependencies
docker compose -f docker-compose.full-stack.yml up postgres keycloak redis rabbitmq

# Run core-java locally (IntelliJ)
# Set environment: SPRING_PROFILES_ACTIVE=local
# OR run with: ./gradlew bootRun --args='--spring.profiles.active=local'
```

### Mode 2: Standalone (Pure Local Development)
**Run everything locally without Docker**

- ✅ **Best for:** Offline development, full control
- ✅ **No Docker required:** All services run natively
- ⚠️ **Requires:** Local PostgreSQL 15+, local Keycloak setup
- 🔧 **Configuration:** Use default profile (no profile set)
- 🔧 **Database:** PostgreSQL on default port 5432

```bash
# Prerequisites:
# - PostgreSQL 15+ installed and running on port 5432
# - Database 'jtoye' created with the runtime role 'jtoye_runtime' (DB_USER) and the
#   owner/migrator role 'jtoye_app' (DB_MIGRATION_USER) — infra/db/create-runtime-role.sql
# - Keycloak installed and running on port 8085

# Run core-java locally. Use the launcher — it sources .env then core-java/.env and
# fails loudly on a missing/forbidden DB_USER, which a bare bootRun does not.
./scripts/run-app.sh

# Equivalent by hand (all three are required; DB_PORT alone leaves the base default
# role and an EMPTY password, which does not authenticate):
#   DB_PORT=5432 DB_USER=jtoye_runtime DB_PASSWORD="$DB_PASSWORD" ./gradlew :core-java:bootRun
```

### Configuration Summary

| Setting | Hybrid Mode | Standalone Mode |
|---------|------------|-----------------|
| **Spring Profile** | `local` | (none/default) |
| **DB_HOST** | `localhost` | `localhost` |
| **DB_PORT** | `5433` | `5432` |
| **DB_USER** | `jtoye_runtime` | `jtoye_runtime` |
| **DB_MIGRATION_USER** | `jtoye_app` (Flyway only) | `jtoye_app` (Flyway only) |
| **PostgreSQL** | Docker container | Local installation |
| **Keycloak** | Docker container (8085) | Local installation (8085) |

⚠️ **CRITICAL SECURITY:** Never use the `jtoye` superuser for the application — it bypasses RLS.
And do not use `jtoye_app` either: it OWNS the public tables, and `DatabaseConfigurationValidator`
refuses to boot as a table owner. The application role is `jtoye_runtime`; `jtoye_app` is reached
only through `DB_MIGRATION_USER`, by Flyway.

---

## Environment Files Required

### File Structure

```
JToye_OaaS_2026/
├── frontend/
│   ├── .env.local.example    ← Template (committed to Git)
│   └── .env.local            ← Your copy (NOT in Git)
├── core-java/
│   ├── .env.example          ← Template (committed to Git)
│   └── .env                  ← Your copy (NOT in Git)
├── edge-go/
│   ├── .env.example          ← Template (committed to Git)
│   └── .env                  ← Your copy (NOT in Git)
└── infra/
    ├── .env.example          ← Template (committed to Git)
    └── .env                  ← Your copy (NOT in Git)
```

---

## Platform-Specific Instructions

### Windows (Command Prompt)

```cmd
cd C:\path\to\JToye_OaaS_2026

REM Frontend
cd frontend
copy .env.local.example .env.local

REM Core Java
cd ..\core-java
copy .env.example .env

REM Edge Go
cd ..\edge-go
copy .env.example .env

REM Infrastructure
cd ..\infra
copy .env.example .env

cd ..
```

### Windows (PowerShell)

```powershell
cd C:\path\to\JToye_OaaS_2026

# Frontend
cd frontend
Copy-Item .env.local.example .env.local

# Core Java
cd ..\core-java
Copy-Item .env.example .env

# Edge Go
cd ..\edge-go
Copy-Item .env.example .env

# Infrastructure
cd ..\infra
Copy-Item .env.example .env

cd ..
```

### Linux / macOS (Bash/Zsh)

```bash
cd /path/to/JToye_OaaS_2026

# Frontend
cd frontend
cp .env.local.example .env.local

# Core Java
cd ../core-java
cp .env.example .env

# Edge Go
cd ../edge-go
cp .env.example .env

# Infrastructure
cd ../infra
cp .env.example .env

cd ..
```

---

## Configuration Details

### 1. Frontend Configuration (`frontend/.env.local`)

**Purpose:** Configures Next.js application and NextAuth.js authentication

```bash
# Backend API
NEXT_PUBLIC_API_URL=http://localhost:9090

# Keycloak OAuth2
KEYCLOAK_CLIENT_ID=core-api
KEYCLOAK_CLIENT_SECRET=CHANGE_ME
KEYCLOAK_ISSUER=http://localhost:8085/realms/jtoye-dev
NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8085/realms/jtoye-dev

# NextAuth
NEXTAUTH_URL=http://localhost:3000
NEXTAUTH_SECRET=your-nextauth-secret-change-in-production
```

**Key Points:**
- `NEXT_PUBLIC_*` variables are embedded at build time and visible in browser
- `KEYCLOAK_CLIENT_SECRET` is server-side only (never exposed to browser)
- `NEXTAUTH_SECRET` should be a random 32+ character string

**Generate NEXTAUTH_SECRET:**
- **Linux/Mac:** `openssl rand -base64 32`
- **Windows PowerShell:**
  ```powershell
  -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object {[char]$_})
  ```

---

### 2. Core Java Configuration (`core-java/.env`)

**Purpose:** Configures Spring Boot backend API

```bash
# Database
DB_HOST=localhost
DB_PORT=5433
DB_NAME=jtoye
DB_USER=jtoye_runtime
DB_PASSWORD=CHANGE_ME
DB_MIGRATION_USER=jtoye_app
DB_MIGRATION_PASSWORD=CHANGE_ME

# Server
SERVER_PORT=9090

# Keycloak
KC_ISSUER_URI=http://localhost:8085/realms/jtoye-dev

# Spring Profile
SPRING_PROFILES_ACTIVE=dev
```

**Key Points:**
- `DB_PORT=5433` connects to PostgreSQL running in Docker
- `KC_ISSUER_URI` must match JWT token issuer exactly
- Default values are suitable for local development

---

### 3. Edge Go Configuration (`edge-go/.env`)

**Purpose:** Configures Go API gateway

```bash
# Core API
CORE_API_URL=http://localhost:9090

# Keycloak
KC_ISSUER_URI=http://localhost:8085/realms/jtoye-dev

# Server
PORT=8080
```

**Key Points:**
- `PORT=8080` is internal; Docker maps this to 8089 on host
- Edge service proxies requests to core-java API

---

### 4. Infrastructure Configuration (`infra/.env`)

**Purpose:** Configures Docker Compose infrastructure (PostgreSQL, Keycloak)

```bash
# Database
DB_HOST=postgres
DB_PORT=5432
DB_NAME=jtoye
DB_USER=jtoye_runtime
DB_PASSWORD=CHANGE_ME
DB_MIGRATION_USER=jtoye_app
DB_MIGRATION_PASSWORD=CHANGE_ME

# Keycloak
KC_ADMIN=admin
KC_ADMIN_PASSWORD=CHANGE_ME
KC_REALM=jtoye-dev
```

**Key Points:**
- Only needed if running `infra/docker-compose.yml` separately
- Full-stack Docker Compose doesn't need this file

---

## Validation

### 1. Check Files Exist

**Linux/Mac:**
```bash
ls -la frontend/.env.local
ls -la core-java/.env
ls -la edge-go/.env
ls -la infra/.env
```

**Windows (PowerShell):**
```powershell
Test-Path frontend\.env.local
Test-Path core-java\.env
Test-Path edge-go\.env
Test-Path infra\.env
```

### 2. Verify Not Committed to Git

```bash
git status

# Should NOT show .env or .env.local files
# If they appear, they're being tracked (BAD!)
```

### 3. Test Configuration

**Option A: Full-Stack Docker (requires the repo-root `.env`)**
```bash
docker compose -f docker-compose.full-stack.yml up
```

**Option B: Local Development**
```bash
# 1. Start the backing services from the CANONICAL compose file.
#    Not `cd infra && docker compose up -d` — that profile collides on ports
#    5433/8085 and starts neither Redis nor RabbitMQ (see Mode 1 above, and README.md).
docker compose -f docker-compose.full-stack.yml up -d postgres keycloak redis rabbitmq

# 2. Start backend (uses core-java/.env)
./scripts/run-app.sh

# 3. Start frontend (uses frontend/.env.local)
cd frontend
npm install
npm run dev
```

### 4. Access Services

- **Frontend:** http://localhost:3000
- **Core API:** http://localhost:9090/actuator/health
- **Edge API:** http://localhost:8080/health (local) or http://localhost:8089/health (Docker)
- **Keycloak:** http://localhost:8085

---

## Troubleshooting

### Problem: "Environment variable X is not defined"

**Cause:** Missing or incorrectly named `.env` file

**Solution:**
1. Verify file exists: `ls -la frontend/.env.local`
2. Check filename exactly (`.env.local` not `.env.local.txt`)
3. Restart the service after creating the file

---

### Problem: "Cannot connect to database"

**Symptoms:**
```
Connection refused: localhost:5433
```

**Solution:**
1. Verify PostgreSQL is running:
   ```bash
   docker ps | grep postgres
   ```
2. Check `DB_PORT=5433` in `core-java/.env`
3. Ensure the backing services are running:
   ```bash
   docker compose -f docker-compose.full-stack.yml up -d postgres keycloak redis rabbitmq
   ```

---

### Problem: "Authentication failed" or "Invalid token"

**Symptoms:**
```
401 Unauthorized
Configuration error
Invalid issuer
```

**Solution:**
1. Verify Keycloak is running:
   ```bash
   curl http://localhost:8085/realms/jtoye-dev
   ```
2. Check `KC_ISSUER_URI` matches in ALL configs:
   - `frontend/.env.local` → `KEYCLOAK_ISSUER`
   - `core-java/.env` → `KC_ISSUER_URI`
   - `edge-go/.env` → `KC_ISSUER_URI`
3. All should be: `http://localhost:8085/realms/jtoye-dev`

---

### Problem: "Frontend can't reach backend API"

**Symptoms:**
```
Network error
CORS error
ERR_CONNECTION_REFUSED
```

**Solution:**
1. Verify core-java is running:
   ```bash
   curl http://localhost:9090/actuator/health
   ```
2. Check `NEXT_PUBLIC_API_URL=http://localhost:9090` in `frontend/.env.local`
3. Verify no firewall blocking port 9090

---

### Problem: Docker Compose fails to start

**Symptoms:**
```
Error: template parsing error
unknown variable
```

**Solution:**
1. For `docker-compose.full-stack.yml`: create the repo-root `.env` from `.env.example` — 18 variables are hard-required
2. For `infra/docker-compose.yml` (the hybrid runtime `scripts/start-dev.sh` drives): create `infra/.env` from `infra/.env.example`
3. Check file encoding is UTF-8 (not UTF-16)

---

### Problem: Changes to .env not taking effect

**Solution:**
1. **Frontend:** Stop (`Ctrl+C`), restart `npm run dev`
2. **Backend:** Stop, restart `./scripts/run-app.sh`
3. **Docker:** `docker compose down && docker compose up`
4. Environment variables are loaded at startup only

---

### Problem: Windows shows "file not found" but file exists

**Cause:** Windows may hide file extensions

**Solution:**
1. Open File Explorer
2. View → Show → File name extensions (enable)
3. Verify file is `.env.local` not `.env.local.txt`

---

### Problem: "Permission denied" on Linux/Mac

**Cause:** File created with wrong permissions

**Solution:**
```bash
chmod 600 frontend/.env.local
chmod 600 core-java/.env
chmod 600 edge-go/.env
chmod 600 infra/.env
```

---

## Security Best Practices

1. **Never commit .env files** - They contain secrets
2. **Use different secrets in production** - Change all passwords/keys
3. **Rotate secrets regularly** - Especially `NEXTAUTH_SECRET`
4. **Restrict file permissions** - chmod 600 on Linux/Mac
5. **Use secret management** - For production (Vault, AWS Secrets Manager)

---

## Next Steps

After setting up environment variables:

1. **Read:** [DOCKER_QUICK_START.md](DOCKER_QUICK_START.md) - Docker deployment
2. **Read:** [USER_GUIDE.md](USER_GUIDE.md) - Using the application
3. **Read:** [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) - Production deployment

---

**Last Updated:** 2026-01-03
**For Issues:** Check project [README.md](../../README.md) or open a GitHub issue
