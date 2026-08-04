# Docker Quick Start Guide

**J'Toye OaaS - Full Stack Docker Setup**

## Step 0: Create your `.env` (required)

`docker-compose.full-stack.yml` has **18 hard-required variables** (`${VAR:?}` form). Without them
even `docker compose config` fails, so this is the first step, not an optional one:

```bash
cp .env.example .env
# then fill in every CHANGE_ME value
bash scripts/verify-env.sh        # fails loudly if anything is missing or weak
```

Check it before going further:

```bash
docker compose -f docker-compose.full-stack.yml config --services
```

If that prints the service list you are ready. If it prints
`required variable POSTGRES_PASSWORD is missing a value`, your `.env` is not in place.

## One-Command Setup

```bash
docker compose -f docker-compose.full-stack.yml up -d --build
```

> Use `docker compose` (v2, a docker subcommand). The old standalone `docker-compose` v1 binary is
> not installed on supported setups — `docker-compose ...` exits `127 command not found`.

Wait 1-2 minutes for all services to start, then access:
- **Frontend**: http://localhost:3000
- **Core API**: http://localhost:9090
- **Keycloak**: http://localhost:8085

---

## What Gets Started

`docker-compose.full-stack.yml` defines 14 services:

| Service | Port | Description |
|---------|------|-------------|
| PostgreSQL | 5433 | Database with RLS policies |
| Keycloak | 8085 | Identity provider (OIDC) |
| keycloak-realm-render | - | Renders the realm import from `.env` before Keycloak boots |
| Redis | 6379 | Cache |
| RabbitMQ | 5672, 15672, 61613 | Message queue + management UI + STOMP |
| Core Java API | 9090 | Spring Boot backend |
| Edge Go Gateway | 8089 | Go API gateway |
| Frontend | 3000 | Next.js 16 UI |
| MCP server | 9100 | Model Context Protocol tool server |
| MinIO | 9000, 9001 | S3-compatible object storage for media |
| minio-init | - | Creates the media bucket, then exits |
| Mailhog | 8025 | Local SMTP inbox for email testing |
| Ollama | 11434 | Local LLM for image analysis |
| ollama-init | - | Pulls the model, then exits |

Grafana, Prometheus and Alertmanager are **not** in this file — they are a separate compose
project, `infra/monitoring/docker-compose.monitoring.yml`.

---

## Test the System

### 1. Test Authentication

```bash
# Open browser
http://localhost:3000/auth/signin
```

This page has **no username/password fields** — it is a single **Sign in with Keycloak** button that
redirects to the identity provider. You type your credentials on Keycloak's own page, not this one.

Sign in as `tenant-a-user`, with the value of `KC_SEED_USER_PASSWORD` from your `.env`.
(`admin-user` works too, with the same seeded password.)

### 2. Test API (CRUD Operations)

```bash
# Get an access token. Credentials come from your .env — never hardcode them.
set -a; . ./.env; set +a

TOKEN=$(curl -s -X POST \
  http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=$KEYCLOAK_CLIENT_ID" \
  -d "client_secret=$KEYCLOAK_CLIENT_SECRET" \
  -d "username=tenant-a-user" \
  -d "password=$KC_SEED_USER_PASSWORD" \
  -d "grant_type=password" | jq -r '.access_token')

# Set tenant ID
TENANT_ID="00000000-0000-0000-0000-000000000001"

# CREATE - Add a customer
curl -X POST http://localhost:9090/api/v1/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Customer","email":"test@example.com","phone":"+1234567890"}'

# READ - Get all customers
curl -X GET http://localhost:9090/api/v1/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID"

# UPDATE - Update a customer (replace {id} with actual ID)
curl -X PUT http://localhost:9090/api/v1/customers/{id} \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "Content-Type: application/json" \
  -d '{"name":"Updated Customer","email":"updated@example.com","phone":"+9876543210"}'

# DELETE - Delete a customer
curl -X DELETE http://localhost:9090/api/v1/customers/{id} \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: $TENANT_ID"
```

---

## Important Notes

### API Endpoints

**✅ Correct:** `http://localhost:9090/api/v1/customers`
**❌ Wrong:** `http://localhost:9090/customers` → `404 Not Found`

The business API **is** served under `/api/v1`. `WebConfig.configurePathMatch` applies
`API_V1_PREFIX = "/api/v1"` to these controller packages, so the prefix does not appear in any
`@RequestMapping` and is easy to miss when reading the source:

- `/api/v1/customers`
- `/api/v1/products`
- `/api/v1/orders`
- `/api/v1/shops`
- `/api/v1/financial-transactions`
- `/api/v1/sync/batch`
- `/api/v1/onboarding`

**Not** prefixed — these keep literal paths and 404 if you add `/api/v1`:

| Path | Why |
|------|-----|
| `/health`, `/actuator/**` | Infrastructure endpoints, outside the versioned API |
| `/v3/api-docs`, `/swagger-ui.html` | OpenAPI surface |
| `/public/**` | Public storefront (`PublicStorefrontController` maps both `/public` and `/api/v1/public`) |
| `/public/payments/**` | Stripe webhook path, deliberately excluded — see `RefundController` javadoc |

### Required Headers
```
Authorization: Bearer <token>
X-Tenant-Id: <tenant-uuid>
```

Note: Header name is `X-Tenant-Id` (lowercase 'd'), not `X-Tenant-ID`.

### Tenant IDs
- Tenant A: `00000000-0000-0000-0000-000000000001`
- Tenant B: `00000000-0000-0000-0000-000000000002`

---

## Common Commands

```bash
# View logs
docker compose -f docker-compose.full-stack.yml logs -f

# View specific service logs
docker compose -f docker-compose.full-stack.yml logs -f frontend
docker compose -f docker-compose.full-stack.yml logs -f core-java
docker compose -f docker-compose.full-stack.yml logs -f keycloak

# Check service status
docker compose -f docker-compose.full-stack.yml ps

# Restart a service
docker compose -f docker-compose.full-stack.yml restart frontend

# Stop all services
docker compose -f docker-compose.full-stack.yml down

# Stop and remove volumes (clean slate)
docker compose -f docker-compose.full-stack.yml down -v

# Rebuild and restart
docker compose -f docker-compose.full-stack.yml up -d --build
```

> **Address services by compose service name, not container name.** `core-java` has no
> `container_name` (it was removed so `--scale core-java=N` works), so `docker logs jtoye-core-java`
> fails with `No such container`. Its real name is generated, e.g.
> `jtoye_oaas_2026-core-java-1`. `docker compose ... logs <service>` is stable across scaling and
> across project-directory renames; prefer it everywhere.

---

## Networking Architecture

### How OAuth Works in Docker

1. **Browser** accesses `http://localhost:8085` (Keycloak on host)
2. **Keycloak** issues token with issuer: `http://localhost:8085/realms/jtoye-dev`
3. **Frontend container** validates token:
   - Has `extra_hosts: localhost:host-gateway` to reach host's localhost
   - Uses `KEYCLOAK_ISSUER: http://localhost:8085/realms/jtoye-dev`
   - Token issuer matches expected issuer ✅
4. **Success** - User authenticated

### Why This Works

- **No /etc/hosts required** - Everything uses localhost
- **Keycloak accessible from both:**
  - Browser: `localhost:8085` → Host port mapping
  - Frontend container: `localhost:8085` → `host-gateway` extra_hosts entry
- **Token issuer consistency** - All components use `localhost:8085`

---

## Troubleshooting

### `docker-compose: command not found`

Use Compose v2: `docker compose` (a `docker` subcommand), not the removed standalone
`docker-compose` v1 binary. Check with `docker compose version`.

### `required variable POSTGRES_PASSWORD is missing a value`

You have no `.env`, or it is incomplete. See **Step 0** above.

### Authentication Error "Configuration"

If you see this error:
```
GET http://localhost:3000/api/auth/error?error=Configuration
Status 500
```

**Cause:** Frontend can't validate the token because issuer mismatch.

**Solution:**
```bash
# Recreate frontend and keycloak with correct configuration
docker compose -f docker-compose.full-stack.yml up -d --force-recreate keycloak frontend
```

### Port Conflicts

If ports are already in use:
```bash
# Find what's using the port
lsof -i :9090
lsof -i :3000
lsof -i :8085

# Kill the process or stop the service
```

### Services Not Healthy

```bash
# Wait longer (services take 1-2 minutes to start)
sleep 60

# Check health status
docker compose -f docker-compose.full-stack.yml ps

# If still unhealthy, check logs
docker compose -f docker-compose.full-stack.yml logs --tail 50 core-java
```

### Database Issues

```bash
set -a; . ./.env; set +a

# Convenient shell access. No password is needed: pg_hba.conf trusts connections made from
# inside the container, both over the unix socket and over 127.0.0.1.
docker compose -f docker-compose.full-stack.yml exec postgres psql -U "$POSTGRES_USER" -d jtoye

# Check tenants exist
SELECT * FROM tenants;

# Check RLS is enabled
SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname='public';

# Exit psql
\q
```

> ⚠️ **Never use a `docker exec`/`docker compose exec` psql to check whether a password is
> correct.** The container's `pg_hba.conf` is:
>
> ```
> local  all all                     trust
> host   all all 127.0.0.1/32        trust
> host   all all all                 scram-sha-256
> ```
>
> Every connection made *from inside the container* — socket or `127.0.0.1` alike — matches a
> `trust` line, so **any** password is accepted and the check cannot fail. Verified: the same
> command with `PGPASSWORD=definitely-wrong` still returns a row.
>
> To actually verify a credential, connect from the **host** through the published port, which
> falls through to `scram-sha-256`:
>
> ```bash
> PGPASSWORD="$DB_PASSWORD" psql -h localhost -p 5433 -U "$DB_USER" -d jtoye -c 'select current_user'
> ```

---

## Credentials Reference

Every credential below is read from your `.env`. None is hardcoded here, and none should be typed
from memory — earlier revisions of this table listed literals (`admin`/`admin123`, `jtoye`/`secret`)
that no longer authenticate against anything.

| Service | Username variable | Password variable |
|---------|-------------------|-------------------|
| Keycloak admin console | `KEYCLOAK_ADMIN` | `KEYCLOAK_ADMIN_PASSWORD` |
| Keycloak app users (`tenant-a-user`, `tenant-b-user`, `admin-user`) | fixed usernames | `KC_SEED_USER_PASSWORD` |
| PostgreSQL superuser (admin/migrations only) | `POSTGRES_USER` | `POSTGRES_PASSWORD` |
| PostgreSQL application role (`jtoye_app`, RLS-enforced) | `DB_USER` | `DB_PASSWORD` |
| RabbitMQ | `RABBITMQ_DEFAULT_USER` | `RABBITMQ_DEFAULT_PASS` |
| Redis | - | `REDIS_PASSWORD` |

⚠️ The application must run as `jtoye_app`, never as the `jtoye` superuser. Superusers bypass
row-level security, and `DatabaseConfigurationValidator` refuses to start the app if it detects one.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  Browser (Host)                                             │
│  → http://localhost:3000 (Frontend)                         │
│  → http://localhost:8085 (Keycloak OAuth)                   │
└─────────────────────────────────────────────────────────────┘
                           │
                           │ (port mappings)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Docker Network: jtoye-network                              │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │  Frontend    │  │  Core Java   │  │  Edge Go     │     │
│  │  :3000       │  │  :9090       │  │  :8089       │     │
│  │  extra_hosts:│  │              │  │              │     │
│  │  localhost→  │  │              │  │              │     │
│  │  host-gateway│  │              │  │              │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│         │                 │                 │              │
│         └─────────────────┴─────────────────┘              │
│                           │                                 │
│         ┌─────────────────┼─────────────────┐              │
│         │                 │                 │              │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌──────▼───────┐    │
│  │  Keycloak    │  │  PostgreSQL  │  │  Redis       │    │
│  │  :8080       │  │  :5432       │  │  :6379       │    │
│  │              │  │  (RLS)       │  │              │    │
│  └──────────────┘  └──────────────┘  └──────────────┘    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

**Docker Compose File:** `docker-compose.full-stack.yml`
**For Issues:** Check [`docs/guides/DEPLOYMENT_GUIDE.md`](./DEPLOYMENT_GUIDE.md) Section 8 (Troubleshooting)
