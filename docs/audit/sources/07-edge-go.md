# Edge Gateway (edge-go) Audit
**Auditor persona**: Distributed systems engineer, Go gateway specialty
**Date**: 2026-04-27
**Implementation quality score**: 6/10
**"Does it earn its keep?" verdict**: **Replace with managed gateway** — keep only the WhatsApp handler, fold it into Core or a tiny Lambda/sidecar.

## Inventory
- **LOC**: 2,713 total across all `.go` (production: ~1,028; tests: ~1,153; generated swagger ~530 not counted).
- **Files**:
  - `cmd/edge/main.go` (228) — bootstrap, signal handling, in-process token bucket, signature verifier
  - `cmd/edge/handlers.go` (317) — Health/Ready, SyncBatch passthrough, WhatsApp webhook orchestrator
  - `cmd/edge/types.go` (69), `cmd/edge/docs.go` (55) — swag-friendly DTOs and Swagger UI registration
  - `internal/middleware/jwt.go` (232) — JWKS cache + RS256 validation
  - `internal/core/client.go` (191), `internal/core/orders.go` (116) — Core HTTP client wrapped in one shared `gobreaker.CircuitBreaker`
  - `internal/whatsapp/parser.go` (117) — newline-delimited "qty x product" regex parser
  - Tests: `main_test.go` (384), `client_test.go` (403), `jwt_test.go` (183), `whatsapp_test.go` (35), `parser_test.go` (151), `openapi_test.go` (232)
- **What it actually does**: Two real routes behind JWT — `POST /api/v1/sync/batch` (a thin Bearer/Tenant header re-stamp + JSON pass-through to Core) and `POST /api/v1/webhooks/whatsapp` (the only endpoint doing genuine work: HMAC verify, regex-parse a text message, call Core `/products/search` per item, then call Core `/orders`). Plus `/health`, `/ready`, `/openapi.json`, `/docs/*`. The "rate limiter, JWT, circuit breaker" stack is real but every one of those concerns has a managed equivalent.

## Each capability honestly rated

### Rate limiting — 3/10
A naive in-process token bucket (`main.go:71-102`) keyed on **nothing**: it is a global counter per pod with `RATE_LIMIT_RPS=20` / `BURST=40` defaults. Not tenant-scoped, not user-scoped, not endpoint-scoped. With N replicas the effective ceiling is `N × RPS`, which the codebase already flags as broken: `.planning/codebase/CONCERNS.md:195` says "Horizontal scaling of edge-go breaks rate limiting correctness." On 429 it returns `{"error":"rate limit exceeded"}` with **no `Retry-After` header**, so well-behaved clients can't back off intelligently. There is no bypass for health probes other than middleware ordering luck (in fact `/health` and `/ready` sit *behind* the global limiter — `main.go:153` registers it before the probe routes, so a noisy neighbour can starve kubelet probes and cause restart loops). This is the textbook anti-pattern that distributed gateways exist to solve.

### JWT middleware — 6/10
Reasonably correct but missing pieces. **Wins**: signing-method allowlist rejects anything that isn't `*jwt.SigningMethodRSA` (`jwt.go:101`) so `alg:none` is shut out; issuer is checked (`jwt.go:153`); 30s leeway is sane; tenant ID is extracted from three claim aliases. **Gaps**: (1) **no audience (`aud`) check** — any RS256 token from the configured issuer is accepted regardless of which client it was minted for. (2) JWKS refresh is a 5-minute polled `time.Since` check on the request path — first request after the window blocks on Keycloak and there is no singleflight, so a thundering herd will hammer JWKS. (3) On unknown `kid` it does an inline refresh inside the parse callback with the full `jwksHTTPClient.Do` call (`jwt.go:121-128`), again under any contention this serializes per-request. (4) `m.publicKeys` and `m.lastRefresh` are read/written without a mutex — the test suite never exercises concurrent traffic, so the data race is latent but real. (5) If Keycloak is down at startup, no keys are pre-loaded; the first request blocks, then 401s with a generic "invalid token" — no graceful degradation, no last-known-good cache persistence. Kong/Envoy give all of this for free, mutex-correct, with stale-while-revalidate.

### Circuit breaker — 5/10
One global `gobreaker.CircuitBreaker` shared across **all four Core operations** (`SyncBatch`, `SearchProducts`, `CreateOrder`, `ForwardWebhook`). A flaky `/products/search` will trip the breaker and shed `/sync/batch` traffic too, even though they're independent failure domains. Thresholds (`client.go:35-55`) are reasonable — 60% failure ratio over ≥10 requests, 30s window, 60s timeout, MaxRequests=3 in half-open. **No fallback**: tripped requests just return an error which the handler turns into 502 (`handlers.go:138`). There's no cached response, no graceful degradation, no `Retry-After`. The breaker also doesn't differentiate idempotent reads from non-idempotent writes — once open, both are blocked equally.

### WhatsApp webhook — 7/10
This is the **only piece doing real work** the gateway model justifies. HMAC-SHA256 signature verification is correct (`hmac.Equal` for constant-time comparison, `main.go:227`), fail-closed when `WHATSAPP_APP_SECRET` is unset (`handlers.go:179`), tolerates the `sha256=` prefix. The parser is sensible: newline-delimited "qty x product" with a single-item fallback, comma-safe so "Eggs, Ham, Cheese" survives. Product resolution is properly paranoid — single hit OR exact case-insensitive title match, otherwise skip with a warning rather than guessing (`handlers.go:259-277`). Always returns 200 to defeat WhatsApp's 3-day exponential retry storm. Real concerns: (1) **`WHATSAPP_DEFAULT_SHOP_ID` is a single env var** — the moment a second tenant onboards WhatsApp this breaks. There is no per-tenant phone-number → shop mapping. (2) The route is mounted behind `jwtMiddleware.Validate()` (`main.go:171-173`), but Meta does **not** send a Bearer token in webhooks — they only sign with HMAC. Either no real WhatsApp traffic ever hits this, or there's an upstream proxy injecting a service-account token. Either way, the auth model is muddled. (3) No idempotency (Meta retries on socket errors); duplicate orders are possible.

### Observability — 2/10
Zap structured logs only. **No Prometheus `/metrics` endpoint** (despite `.planning/phases/15-RESEARCH.md:107` claiming Prometheus scrapes `edge-go:8080`). **No OpenTelemetry / B3 / W3C trace propagation** — incoming `traceparent` headers are dropped on the floor, and the Core HTTP client doesn't forward them, so the Java side's Brave/Zipkin tracing has a black hole at the gateway. No request IDs, no rate-limiter rejection counter, no breaker state metric. Logs are decent but you can't graph anything.

## Code quality
Solid baseline Go, idiomatic Gin. Errors are wrapped with `%w` consistently. Context propagation is correct (`http.NewRequestWithContext`, `context.WithTimeout` with `defer cancel()`). The rate-limiter goroutine is properly tied to a SIGINT-derived `signal.NotifyContext` (`main.go:121, 153`) — no goroutine leak. `defer resp.Body.Close()` everywhere. Test coverage is genuinely good for a bespoke gateway: 28 logical tests, including circuit-breaker behaviour, context cancellation, and table-driven tenant extraction. **Bugs/smells**: (a) `JWTMiddleware.publicKeys` map mutation without a mutex (data race under load); (b) the "valid token" middleware test (`jwt_test.go:77-128`) silently expects 401 because it never wires a real JWKS — it's a placeholder masquerading as a test; (c) the rate-limiter sits in front of `/health` and `/ready`; (d) `getEnvInt` rejects negatives but not `0`, which would `panic` on `time.Second / 0` in the ticker (silent bug if someone misconfigures `RATE_LIMIT_RPS=0`).

## Latency overhead estimate
On a loopback localhost hop with everything warm: **~1-3ms p50, ~5-10ms p99 per request**. JWT RS256 verify is ~100-300µs, JSON marshal/unmarshal of typical payloads ~200µs-1ms, gobreaker sync overhead negligible (~µs), the extra HTTP hop dominates (~1-2ms). On JWKS-refresh requests the tail jumps to whatever Keycloak takes to respond (potentially tens of ms). At the network level, you're paying for one extra TCP hop, one extra TLS handshake (in prod), and one extra service to monitor. For the volume this gateway actually carries (one batch endpoint and a webhook), the latency is irrelevant — the operational cost of running a 1k-LOC bespoke service for two routes is the real tax.

## Build-vs-buy analysis
- **What edge-go provides that managed alternatives don't**:
  - The WhatsApp parser + product-resolution + order-create orchestration. That's domain logic, not gateway behaviour, and would belong in Core or a Lambda regardless.
  - Tenant-claim aliasing (`tenant_id`/`tenantId`/`tid`) — but this is 6 lines of code and Kong has plugins for the same.
- **What edge-go lacks that managed alternatives give for free**:
  - Distributed (Redis-backed or shared-state) rate limiting — Kong, Envoy/Istio, AWS API Gateway, Traefik all ship this.
  - Per-route / per-consumer / per-tenant quotas with `Retry-After`.
  - Audience validation, JWKS refresh with singleflight + stale-while-revalidate, mutex-safe key cache.
  - OTel/B3 trace propagation, Prometheus metrics, request IDs.
  - WAF, IP allow-lists, mTLS to upstream, automatic retries with jitter.
  - Admin UI, declarative config, plugin ecosystem (CORS, transformations, rate limit, auth, caching, OIDC introspection).
  - High availability without a custom Helm chart.
- **Cost estimate (eng-hours/month to maintain)**: ~4-8 hours/month steady state (Go version pin upgrades, dependency CVEs, swag pin gymnastics — see `.planning/phases/16-RESEARCH.md:131` where swag is frozen at v1.16.3 because v1.16.5+ requires Go 1.23 and the repo is locked to 1.22). Plus ~16 hours one-off to fix the documented distributed rate-limiter bug (`CONCERNS.md:195`).

## Recommendation

### Option 1 — Keep and harden (~3-5 day investment)
- **Pros**: No infra change, tests already exist, team owns the code.
- **Cons**: Have to fix distributed rate limiting (Redis), add OTel propagation, add Prometheus metrics, add `aud` claim check, add mutex on JWKS map, move rate limiter behind probes, split breaker per route, add per-tenant WhatsApp shop mapping. You'll be reinventing Kong, badly.

### Option 2 — Replace with Kong / Envoy / Traefik
- **Pros**: Distributed rate limiting, JWKS, OIDC validation, metrics, tracing all configuration not code. Battle-tested. Plugin ecosystem. Frees ~1k LOC + 1.2k LOC of tests to delete. WhatsApp orchestrator becomes a Core controller (`@PostMapping("/api/v1/webhooks/whatsapp")`) sitting behind the same Spring Security stack, reusing the existing TenantContext machinery.
- **Cons**: New infra dependency to operate, learning curve, declarative config to author, ingress topology change.

### Option 3 — Delete and absorb into Core API
- **Pros**: Smallest surface area. Spring Security already validates Keycloak JWTs with `aud` checks, Bucket4j is already a dependency for rate limiting, Resilience4j is already in the stack for circuit breakers, Micrometer/OTel already wired for metrics + tracing. WhatsApp webhook is ~150 lines of Java in a `WhatsAppController`. The only thing you lose is the language firewall (Go binary in front of JVM), which had no security value here.
- **Cons**: Core's blast radius grows slightly; a noisy WhatsApp tenant could affect API throughput unless you isolate via separate tomcat thread pool / virtual threads.

## My pick and why
**Option 3 (delete and absorb).** edge-go is doing 50 LOC of orchestration wrapped in 1,000 LOC of half-built gateway primitives that the Java stack already implements better — Spring Security validates JWTs with audience checks, Bucket4j and Resilience4j are *already* deps in `core-java`, Micrometer is *already* exporting Prometheus and OTel. The WhatsApp handler is the only thing earning rent and it's a Core controller in disguise. If a real public API surface emerges later (mobile apps, partners, multiple upstreams) revisit Option 2 with Kong/Envoy — but don't keep paying maintenance on a bespoke gateway whose own planning docs (`CONCERNS.md:195`) already flag it as architecturally broken at the first horizontal scale event.
