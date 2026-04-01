# Edge Go Module -- Complete Catalog

> **Generated**: 2026-04-01  
> **Module**: edge-go (Go 1.22, Gin)

---

## Purpose

API gateway sitting between external clients and the Core Java backend. Provides JWT validation, circuit breaking, rate limiting, and webhook ingestion.

---

## Package Structure

```
edge-go/
├── cmd/edge/
│   ├── main.go                 # HTTP server, endpoints, rate limiter, WhatsApp handler
│   └── whatsapp_test.go        # HMAC-SHA256 signature verification tests (3 sub-tests)
├── internal/
│   ├── core/
│   │   ├── client.go           # Core API HTTP client with circuit breaker
│   │   └── client_test.go      # 7 tests (health, sync, circuit breaker)
│   └── middleware/
│       ├── jwt.go              # JWT validation middleware (JWKS, RSA, tenant extraction)
│       └── jwt_test.go         # 5 tests (auth headers, tokens, tenant extraction)
├── go.mod                      # Module: github.com/jtoye/edge, Go 1.22
├── Dockerfile                  # Multi-stage: golang:1.22-alpine -> scratch (~15MB)
└── .env.example                # Environment variable template
```

---

## Dependencies (4 direct)

| Package | Version | Purpose |
|---------|---------|---------|
| gin-gonic/gin | v1.10.0 | HTTP framework |
| golang-jwt/jwt/v5 | v5.2.1 | JWT parsing/validation |
| sony/gobreaker | v1.0.0 | Circuit breaker |
| uber-go/zap | v1.27.0 | Structured logging |

---

## Endpoints

| Method | Path | Auth | Handler |
|--------|------|------|---------|
| GET | `/health` | None | Probes Core API health (2s timeout), returns service + dependency status |
| POST | `/sync/batch` | JWT | Extracts tenant_id from JWT context, forwards to Core API `/sync/batch` |
| POST | `/webhooks/whatsapp` | HMAC-SHA256 | Verifies `X-Hub-Signature-256`, logs receipt, returns 204 |

---

## Circuit Breaker (Core API Client)

| Setting | Value |
|---------|-------|
| Name | "CoreAPI" |
| Max Requests (Half-Open) | 3 |
| Interval | 10 seconds |
| Timeout | 60 seconds |
| Failure Threshold | >= 60% failure ratio |

State transitions logged via zap.

---

## Rate Limiter

- Algorithm: Token bucket
- Rate: 20 requests/second
- Burst: 40 tokens
- Scope: Global (not per-tenant)
- Response: HTTP 429 when exceeded
- Note: `.env.example` lists `RATE_LIMIT_RPS` and `RATE_LIMIT_BURST` vars but code hardcodes values

---

## JWT Middleware

- Fetches RSA public keys from Keycloak JWKS endpoint
- 5-minute key cache with automatic refresh
- Validates: signature (RSA), issuer, expiration
- Extracts tenant from claims: `tenant_id` > `tenantId` > `tid` (priority order)
- Stores in Gin context: `jwt_claims`, `tenant_id`, `user_id`

---

## WhatsApp Webhook

- Header: `X-Hub-Signature-256`
- Secret: `WHATSAPP_APP_SECRET` environment variable
- Verification: HMAC-SHA256 with constant-time comparison
- Handles `sha256=` prefix in signature
- **Current status**: Logs receipt but does NOT forward to Core API (TODO)

---

## Environment Variables

| Variable | Default | Required |
|----------|---------|:--------:|
| `CORE_API_URL` | `http://localhost:9090` | Yes |
| `KC_ISSUER_URI` | `http://localhost:8085/realms/jtoye-dev` | Yes |
| `PORT` | `8080` | No |
| `WHATSAPP_APP_SECRET` | (none) | No |

---

## Docker Image

- Builder: `golang:1.22-alpine`, static binary with stripped symbols
- Runtime: `scratch` (minimal, ~10-15MB)
- Health check: `/edge health-check` command
- Port: 8080

---

## Tests (12 total, 100% passing)

| File | Tests | Coverage |
|------|-------|----------|
| `whatsapp_test.go` | 3 sub-tests | Signature verification (valid, prefixed, invalid) |
| `client_test.go` | 7 tests | Health success/failure/timeout, sync success/error, circuit breaker, init |
| `jwt_test.go` | 5 tests | Missing header, invalid format, invalid token, valid token, tenant extraction |
