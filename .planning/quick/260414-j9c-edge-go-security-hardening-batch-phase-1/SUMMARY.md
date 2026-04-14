# Quick Task 260414-j9c — SUMMARY

**Status:** ✅ Complete (PR not yet opened — held until all audit phases done)
**Branch:** `fix/edge-go-security-hardening`
**Commits:** 11 / 11
**Tests:** 4 packages, 28 tests, all PASS

## Commits

| # | SHA | Subject | Files |
|---|-----|---------|-------|
| 1 | c22460c | fix(edge): bounds-check bearer token extraction | edge-go/cmd/edge/main.go |
| 2 | fbf799d | fix(edge): fail-closed when WhatsApp app secret is unset | edge-go/cmd/edge/main.go |
| 3 | 01abcd5 | fix(edge): add 5s timeout on JWKS fetch | edge-go/internal/middleware/jwt.go |
| 4 | c5c94eb | fix(edge): reject WhatsApp webhook without valid bearer token | edge-go/cmd/edge/main.go |
| 5 | dfb8239 | fix(edge): stop rate-limiter ticker on shutdown | edge-go/cmd/edge/main.go |
| 6 | 8fda9c6 | fix(edge): add 30s JWT clock-skew leeway | edge-go/internal/middleware/jwt.go |
| 7 | 43d7ccf | fix(edge): make JWKS refresh interval configurable | edge-go/internal/middleware/jwt.go, .env.example, README.md |
| 8 | 56849e3 | fix(edge): rewrite WhatsApp parser to be newline-delimited | edge-go/internal/whatsapp/parser{,_test}.go |
| 9 | 34ea852 | fix(edge): require confident product match | edge-go/cmd/edge/main.go |
| 10 | 931a16b | fix(edge): warm-up window for core-api circuit breaker | edge-go/internal/core/client.go |
| 11 | 5da6459 | fix(edge): split /health liveness from /ready readiness | edge-go/cmd/edge/main.go, k8s/base/edge-go-deployment.yaml |

## Test gate

```
ok  github.com/jtoye/edge/cmd/edge
ok  github.com/jtoye/edge/internal/core
ok  github.com/jtoye/edge/internal/middleware
ok  github.com/jtoye/edge/internal/whatsapp
```

## Deviations
1. Fix #9 used `Title` field (verified at `edge-go/internal/core/orders.go:17`), not "Name" as plan suggested.
2. Fix #5 chose `signal.NotifyContext` + explicit `http.Server.Shutdown` over plain `WithCancel` — ties rate-limiter goroutine lifetime to actual SIGTERM drain.
3. Fix #2 collapsed a redundant `io.ReadAll` (body now read once for HMAC and reused).
4. Fix #11 uses a fresh 2s `http.Client` for the readiness JWKS probe (tighter SLO than the 5s middleware client).

## Breaking change
- WhatsApp parser grammar is now **one item per line**. Senders must put each item on its own line. Documented in commit body of `56849e3`.
