# Quick Task 260414-j9c: Edge-Go Security Hardening (Audit Phase 1)

**Created:** 2026-04-14
**Branch:** fix/edge-go-security-hardening
**Goal:** Close 11 verified security + reliability gaps in `edge-go/` found in codebase audit, with atomic commits and passing `go test ./...` after each change.

## Verified findings (file:line evidence pre-confirmed)

| # | File | Line | Issue | Fix |
|---|------|------|-------|-----|
| 1 | `edge-go/cmd/edge/main.go` | 126 | `c.GetHeader("Authorization")[7:]` — no bounds check, panics on `Bearer` or shorter | Extract `extractBearerToken(*gin.Context) (string, bool)` helper; use at lines 126 and 195-198 |
| 2 | `edge-go/cmd/edge/main.go` | 147-172 | WhatsApp webhook fails-open when `WHATSAPP_APP_SECRET` unset | Fail-closed: if secret empty, return 500 `webhook signing not configured` and warn |
| 3 | `edge-go/internal/middleware/jwt.go` | 149 | `http.Get(m.jwksURL)` no timeout | Package-level `http.Client{Timeout: 5*time.Second}`, use `client.Do(ctx, req)` |
| 4 | `edge-go/cmd/edge/main.go` | 195-246 | Empty `token` passed to `coreClient.CreateOrder` | Require extracted token; reject webhook without auth |
| 5 | `edge-go/cmd/edge/main.go` | 24-49 | Rate limiter ticker/goroutine never stopped | Accept `context.Context`; `defer ticker.Stop()`; wire to shutdown ctx |
| 6 | `edge-go/internal/middleware/jwt.go` | ~69 | JWT has no clock-skew leeway | Add `jwt.WithLeeway(30*time.Second)` parse option |
| 7 | `edge-go/internal/middleware/jwt.go` | 82 | 5-min JWKS refresh hardcoded | Read `JWKS_REFRESH_INTERVAL` env var (default 5m) |
| 8 | `edge-go/internal/whatsapp/parser.go` | 41 | Regex `(?:,|$)` truncates `"2x Eggs, Ham, Cheese"` | Switch to newline-anchored delimiter; add regression test |
| 9 | `edge-go/cmd/edge/main.go` | 209-224 | Uses `products[0]` blindly | Require single match or name equality; else log+skip item |
| 10 | `edge-go/internal/core/client.go` | 26-34 | Circuit breaker trips from cold start | `Requests >= 10 && failureRatio >= 0.6`; `Interval: 30s` |
| 11 | `edge-go/cmd/edge/main.go` | 89-108 | `/health` doesn't ping JWKS | Add `/ready` that also pings JWKS; keep `/health` as liveness |

## Commit sequence (one conventional commit per fix)

1. `fix(edge): bounds-check bearer token extraction (CVE-candidate panic)`
2. `fix(edge): fail-closed when WhatsApp app secret is unset`
3. `fix(edge): add 5s timeout on JWKS fetch`
4. `fix(edge): reject WhatsApp webhook without valid bearer token`
5. `fix(edge): stop rate-limiter ticker on shutdown`
6. `fix(edge): add 30s JWT clock-skew leeway`
7. `fix(edge): make JWKS refresh interval configurable`
8. `fix(edge): rewrite WhatsApp parser to be newline-delimited`
9. `fix(edge): require confident product match in WhatsApp order`
10. `fix(edge): add warm-up window to core-api circuit breaker`
11. `fix(edge): split /health liveness from /ready readiness`

## Test gates (run after EACH commit)

```bash
cd edge-go && go build ./... && go test ./...
```

Stop on failure, investigate root cause, fix before next commit.

## Exit criteria

- 11 commits on `fix/edge-go-security-hardening`
- `go test ./...` green on every commit
- Each commit is atomic and has conventional-commit message
- `SUMMARY.md` written in this quick-task dir listing commit hashes
- PR opened to main titled `fix: edge-go security hardening (phase 1 of audit)`
- No Co-Authored-By trailers
