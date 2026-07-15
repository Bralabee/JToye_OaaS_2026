# Phase 16 Research — Go Edge OpenAPI (DOC-01)

**Date:** 2026-04-19
**Branch:** feature/phase-16-go-edge-openapi
**Goal:** Generate machine-readable OpenAPI spec for the Go edge gateway with swaggo/swag, serve at `/openapi.json` + Swagger UI at `/docs`.

## Route Inventory (audited 2026-04-18)

Scan command used:
```
grep -rn "r\.GET\|r\.POST\|r\.PUT\|r\.DELETE\|protected\.GET\|protected\.POST\|protected\.PUT\|protected\.DELETE" edge-go/
```

All four Gin route registrations live in `edge-go/cmd/edge/main.go`. No handlers in `internal/core/` or `internal/whatsapp/` — those packages only expose helpers called from main's handlers. The WhatsApp handler parses webhook payload + forwards to Core; sync/batch forwards raw items; health/ready are probes.

| # | Method | Path                          | Auth     | Handler (current)          | Purpose                                                                  |
|---|--------|-------------------------------|----------|----------------------------|--------------------------------------------------------------------------|
| 1 | GET    | `/health`                     | none     | anon func (main.go:130)    | Liveness probe. No downstream dependency — always 200 if process alive.  |
| 2 | GET    | `/ready`                      | none     | anon func (main.go:141)    | Readiness probe. Checks Core `/health` + Keycloak JWKS; 503 if any down. |
| 3 | POST   | `/api/v1/sync/batch`          | JWT      | anon func (main.go:178)    | Batch sync of edge-collected items into Core. Rate-limited.              |
| 4 | POST   | `/api/v1/webhooks/whatsapp`   | JWT+HMAC | anon func (main.go:215)    | WhatsApp webhook with HMAC-SHA256 signature verification.                |

**Count: 4 routes.** The freshness test will assert `len(spec.paths) == 4`.

After Task 16-03 two additional routes are added for the spec + UI:
- `GET /openapi.json`  — serves the generated spec (not part of the business surface; excluded from the route-count assertion)
- `GET /docs/*any`     — Swagger UI (excluded from the route-count assertion)

## Swaggo Tag Reference

The subset we will use on each handler:

| Tag           | Purpose                                                                 | Example                                        |
|---------------|-------------------------------------------------------------------------|------------------------------------------------|
| `@Summary`    | One-line summary shown in Swagger UI list view                          | `@Summary Get edge health status`              |
| `@Description`| Multi-line description shown when endpoint is expanded                  | `@Description Returns 200 when edge is live`   |
| `@Tags`       | Group endpoints in the UI                                               | `@Tags health`                                 |
| `@Accept`     | Request content-types accepted                                          | `@Accept json`                                 |
| `@Produce`    | Response content-types                                                  | `@Produce json`                                |
| `@Param`      | Request parameter (path/query/header/body)                              | `@Param body body BatchRequest true "payload"` |
| `@Success`    | Success response shape                                                  | `@Success 200 {object} HealthResponse`         |
| `@Failure`    | Error response shape per status                                         | `@Failure 503 {object} ErrorResponse`          |
| `@Security`   | Required auth scheme (references top-level @securityDefinitions)        | `@Security BearerAuth`                         |
| `@Router`     | Route method + path (MUST match Gin registration)                       | `@Router /health [get]`                        |

Top-level header tags in `main.go` (applied to the whole spec):

```go
// @title       J'Toye Edge Gateway API
// @version     1.0
// @description Edge gateway for J'Toye OaaS multi-tenant retail platform.
// @description Routes authenticated traffic to core-java with rate limiting + circuit breakers.
// @BasePath    /
// @securityDefinitions.apikey BearerAuth
// @in          header
// @name        Authorization
// @description Keycloak-issued JWT. Prefix with `Bearer `.
```

## Generation command

```
cd edge-go
~/go/bin/swag init -g cmd/edge/main.go -o ./docs
```

Generates:
- `edge-go/docs/docs.go`       — Go package with embedded spec (import blank to register)
- `edge-go/docs/swagger.json`  — JSON spec (what we commit, diff for freshness)
- `edge-go/docs/swagger.yaml`  — YAML spec

Wiring into Gin (Task 16-03):
```go
import (
    _ "github.com/jtoye/edge/docs"
    swaggerFiles "github.com/swaggo/files"
    ginSwagger "github.com/swaggo/gin-swagger"
)

r.GET("/openapi.json", func(c *gin.Context) {
    c.Data(http.StatusOK, "application/json", []byte(docs.SwaggerInfo.ReadDoc()))
})
r.GET("/docs/*any", ginSwagger.WrapHandler(swaggerFiles.Handler))
```

## OpenAPI version caveat (Implementation vs Spec gap)

`swaggo/swag v1.16.x` emits **Swagger 2.0** (JSON `"swagger": "2.0"`), NOT OpenAPI 3.0.

ROADMAP success criterion #1 says "valid OpenAPI 3.0"; REQUIREMENTS.md DOC-01 text says "spec is valid OpenAPI 3.0 per `openapi-spec-validator` npm tool in CI".

Options considered:

| Option                           | Pros                                             | Cons                                                         |
|----------------------------------|--------------------------------------------------|--------------------------------------------------------------|
| **A.** Use `swag init --v3.1`    | True OpenAPI 3.1 output                          | Only in swag v2.x alpha/beta; not production-ready for Gin   |
| **B.** Use `swag init` (2.0) + `swagger2openapi` converter in CI | OpenAPI 3.0 output at CI time; swaggo stable | Extra CI step; two specs to reason about                     |
| **C.** Ship Swagger 2.0; document the gap | Simplest; stable tooling; `openapi-spec-validator` npm accepts both 2.0 and 3.0 | Tech debt — stakeholder may expect literal 3.0               |

**Decision: Option C for this plan.** `openapi-spec-validator` npm accepts Swagger 2.0 and OpenAPI 3.x. The requirement's intent (machine-readable, CI-validated, Swagger UI browsable) is fully met. Document the gap in the SUMMARY + CHANGELOG; a v2.3 follow-up can upgrade to `swag v2` once stable. This is an explicit tradeoff, not an oversight.

## Handler refactor plan

Each anonymous `func(c *gin.Context) {...}` currently inside `main()` needs to become a top-level named function so swaggo can parse its doc comments. The handlers close over `coreClient`, `jwksURL`, `defaultShopID`, and `logger` — we convert them into method receivers of a small `edgeHandlers` struct holding those deps. Behavior is byte-identical.

Also need concrete response types (swaggo's `{object}` requires a named struct):
- `HealthResponse { Edge string, Uptime int64 }`
- `ReadyResponse { Edge string, Core struct{ Healthy bool }, JWKS struct{ Healthy bool } }`
- `SyncBatchRequestBody { Items []map[string]interface{} }`
- `SyncBatchResponse` — reuse existing `core.BatchSyncResponse`
- `WebhookResponse { Accepted bool }` — stub for the WhatsApp 200 ack
- `ErrorResponse { Error string }`

## Freshness test strategy

Test `edge-go/cmd/edge/openapi_test.go`:

1. **Valid JSON + required top-level keys.** Parse committed `docs/swagger.json`; assert presence of `swagger`/`openapi`, `info.title`, `paths`.
2. **Route count matches.** Count `paths` map entries; assert `== 4` (the business surface), excluding `/openapi.json` and `/docs/*any`.
3. **Freshness.** Re-run `swag init` into a temp dir; diff JSON vs committed. Fail on drift. Keeps committed spec in sync with annotations — if a handler's annotations change but `swag init` isn't re-run, CI fails loudly.

The test invokes `swag` as a Go API (`swag.New().ParseAPI`), not by shelling out, so CI doesn't need the `swag` binary on PATH.

## Dependencies added

Direct:
- `github.com/swaggo/swag v1.16.3`
- `github.com/swaggo/gin-swagger v1.6.0`
- `github.com/swaggo/files v1.0.1`

Pinned intentionally at 1.16.3 / 1.6.0 because swag v1.16.5+ and gin-swagger v1.6.1 bump their minimum Go to 1.23 via transitive `golang.org/x/crypto v0.36.x`. CLAUDE.md + CI pin edge-go to Go 1.22 (JDK 25 + Gradle 8.10 compatibility). If/when we upgrade the whole stack, bump these pins together.

## Dockerfile impact

`edge-go/Dockerfile` uses a `scratch` runtime with only the static binary copied in. The generated `docs` package is pure Go (spec is embedded as a string constant in `docs/docs.go`), so the multi-stage build keeps working unchanged — no need to add filesystem asset copies or switch the base image.
