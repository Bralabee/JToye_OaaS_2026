# QA / Test Engineering Audit
**Auditor persona**: Senior QA lead, production-failure veteran
**Date**: 2026-04-27
**Test quality score**: 7/10
**Coverage of critical paths score**: 6/10
**Trust-this-suite score**: 6/10 — green CI lets me sleep, but I'd keep the pager close

---

## Claim verification (the headline)

Claim from CLAUDE.md: "390 Java @Test methods across 48 files + 76 Jest it/test blocks across 13 files + 50 top-level Go Test* funcs / 54 with t.Run subtests across 5 files = 516+ logical invocations".

Actual counts (run 2026-04-27 from this repo HEAD):

| Surface | Claimed | Actual | Delta |
|---|---|---|---|
| Java test files | 48 | **61** | +13 |
| Java `@Test` annotations | 390 | **432** | +42 |
| Jest test files | 13 | **16** | +3 |
| Jest `it()`/`test()` blocks | 76 | **84** | +8 |
| Go test files | 5 | **6** | +1 |
| Go `func Test*` | 50 | **54** | +4 |
| Go `t.Run` subtests | 54 | **4** (additional) | docs miscounted |
| Playwright specs | not claimed | **4 files / 21 test() blocks** | uncounted |
| Testcontainers test classes | not claimed | **18** | uncounted |

**Verdict**: The claim *understates* current count — the project has grown since the 2026-04-18 verification but the docs were never updated. Total logical invocations are now closer to **595** (432 Java + 84 Jest + 54 Go + 21 Playwright + ~4 Go subtests). The project standard line in CLAUDE.md should be refreshed; numeric drift like this is exactly what the "Release & Documentation Integrity" rule warns about.

The Go `t.Run` figure in the claim ("54 with t.Run subtests") looks wrong — `grep -rhE "t\.Run\("` returns 4. Either the claim conflated subtests with table-driven test cases, or the count was always inflated.

---

## Test quality assessment (sampled 12 files)

### Java (Spring Boot) — overall: solid where it counts
- `MultiTenantIsolationIntegrationTest` (RLS, real Postgres via Testcontainers): exemplary. Asserts `pg_class.relrowsecurity` is `true`, asserts policy count > 0, AND demonstrates tenant A cannot read tenant B by primary key. This is the gold standard.
- `CrossTenantSpoofIntegrationTest` (SEC-01): well-designed — uses `@AutoConfigureMockMvc` with real JWT post-processors, asserts both 403 status AND audit log payload (`event=tenant_spoof_attempt`). Includes regression guards (same-tenant succeeds, anonymous succeeds) so it can't pass vacuously by over-blocking.
- `PaymentServiceTest`: heavy use of `MockedStatic<Webhook>` to stub Stripe SDK. Verifies state transitions, financial transaction creation, event publication. **However** it does not exercise real HMAC signature verification — `Webhook.constructEvent` is mocked entirely. The signature path is a mocked seam, not a verified one.
- `OrderStateMachineServiceTest`: clean coverage of happy path, all CANCEL transitions, key invalid transitions, and validation-only API. Misses some illegal events from intermediate states (e.g. PREPARING→SUBMIT, READY→START_PREP) but the spirit is right.
- `RateLimitInterceptorTest`: extensive Bucket4j mocking, including a dedicated `testTenantIsolation_DifferentTenantsHaveSeparateLimits` case that proves separate buckets per tenant. Excluded-endpoint cases (health, actuator, swagger) are covered. No real Redis test — this is mock-only.
- `JwtTenantFilterTest`: covers all three claim names (`tenant_id`, `tenantId`, `tid`), claim priority, malformed UUID, and missing claims. Does NOT cover expired tokens, wrong audience, or wrong issuer — those would fall to `JwtDecoder` not the filter, but no integration test pins it.
- `StorageServiceTest`: real `BufferedImage` + `ImageIO` to generate JPEG/PNG bytes — magic-byte and dimension validation are tested with actual image data, not stubbed bytes. Catches spoofed content-type. Does not test path traversal (e.g. `../etc/passwd` as filename) or SSRF on `delete()` (relies on URL prefix check only).

### Frontend (Jest + RTL) — overall: thin
- 84 invocations across 16 files is small for a Next.js app of this scope. Many tests are smoke renders (component mounts, expected text appears).
- `cart.test.tsx` is well-written — exercises the localStorage hydration race and asserts both empty + populated states with correct totals.
- `api-client.test.ts` is only 50 lines / 4 tests; `dashboard-shell.test.tsx` is 27 lines. Most page-level tests verify "this page renders some text" rather than user interactions.
- No Jest tests against the auth flow logic, rate-limit toast handling, or SSE/STOMP error paths.

### Go (edge gateway) — overall: shallow
- `jwt_test.go` `TestJWTMiddleware_Validate_ValidToken` is honest about its own gap: comments say "this test will fail validation because we can't easily mock JWKS validation" and accepts 401 either way. **The valid-token path is not actually tested.**
- `client_test.go` uses `httptest` servers nicely, covers timeout. No circuit-breaker open-state test was visible in my sample.
- `whatsapp/parser_test.go` is the deepest Go file — proper table-driven tests including malformed payload cases.

---

## Critical-path coverage matrix

| Path | Covered? | Evidence | Quality |
|---|---|---|---|
| Stripe webhook signature verification | Partial | `PaymentControllerTest:46-60` (signature error path), `PaymentServiceTest:90-96` | **Low** — `Webhook.constructEvent` is mocked everywhere; no test computes a real HMAC and passes it through the actual SDK call. A bug in `stripeProperties.getWebhookSecret()` resolution would not be caught. |
| Cross-tenant isolation (read) | Yes | `MultiTenantIsolationIntegrationTest`, `FinancialSummaryCrossTenantIsolationTest`, `CrossTenantSpoofIntegrationTest` | **High** — real Postgres, RLS asserted at DB level AND application level (slug spoof rejected) |
| Cross-tenant isolation (write) | Yes | `CrossTenantSpoofIntegrationTest:185-206` POST /orders 403 | **High** |
| Order state machine — happy path | Yes | `OrderStateMachineServiceTest` | **High** |
| Order state machine — illegal transitions | Partial | Same file lines 78-101 | **Medium** — covers DRAFT→CONFIRMED, CONFIRMED→PENDING, terminal-state rejection. Does not enumerate every (state, event) combination. |
| JWT validation — happy path | Partial | `JwtTenantFilterTest` (claim extraction only) | **Medium** — Spring Security's `JwtDecoder` is implicitly trusted; no test asserts behaviour with expired token, wrong audience, wrong issuer, or unsigned token at the controller level |
| RLS at DB level (not just service mocks) | Yes | `MultiTenantIsolationIntegrationTest:196-221` queries `pg_class` and `pg_policies` directly | **High** — gold-standard test |
| Rate limiting | Yes (mocked) | `RateLimitInterceptorTest` | **Medium** — no Redis-backed end-to-end test; mocks cover logic but not Bucket4j Redis integration or proxy serialization |
| Image upload validation (magic bytes, dimensions, size) | Yes | `StorageServiceTest` | **High** — uses real image bytes, not stubs |
| Image upload SSRF / path traversal | No | — | **None** — filename like `../../../etc/passwd` is not tested; `delete()` relies on URL string prefix only |
| Payment idempotency | No | `idempotencyKey` field exists in `Order.java`, used in `PublicStorefrontService.java:329`. **Zero tests** verify duplicate POST returns the same order. | **None** — this is a P0 gap. The whole point of the field is to prevent double-charging on retry; there is no regression guard. |
| Refund flow | No (Java test) | Only frontend mention; `PaymentServiceTest` does not cover Stripe refund webhook (`charge.refunded` is in the "ignore" branch) | **Low** — recent PR #51 added vendor refund UI; backend refund handling is not pinned by tests |

---

## E2E coverage

Playwright config exists at `frontend/playwright.config.ts`. Specs:

- `storefront-flows.spec.ts` (15 tests): the strongest E2E in the repo. Real Keycloak login flow, cart-to-order flow including order number assertion, image rendering verified via `naturalWidth > 0` (matches the user's "image rendering" feedback rule), Mailhog assertion for email delivery.
- `kitchen-flow.spec.ts` (1 test).
- `stomp-relay.spec.ts` (2 tests).
- `csp-no-violations.spec.ts` (3 tests).

**Gaps**: vendor admin flows are NOT covered E2E (product CRUD, order management, refund flow, marketing tools). Login flow only tested for storefront customer, not for vendor staff. No cross-browser matrix evidence in CI logs sampled.

---

## Test infrastructure quality

- **Testcontainers**: 18 classes use real Postgres 15 — strong. `CrossTenantSpoofIntegrationTest` even overrides H2 properties from `application-test.yml` to force PG, with a comment explaining why (RLS only exists in PG). This shows real engineering maturity.
- **CI**: `.github/workflows/ci-cd.yaml` runs Java tests, Go tests with `-coverprofile`, and Jest with `--ci --watchAll=false`. **Playwright is NOT run in CI** — the most behavioural tests in the repo are excluded from the merge gate. This is the single biggest infrastructure gap.
- **Coverage reports**: only Go produces `coverage.out`. JaCoCo is not configured for Java; no Jest `--coverage` in CI. We have no idea what % of the 432 Java assertions actually execute branches.
- **Flakiness signals**: 18 `Thread.sleep`/`time.Sleep`/`waitForTimeout` occurrences across test code. Most are in Playwright (acceptable — auth redirects). One in Go `client_test.go` is a 2s sleep simulating slow upstream — fine. No `@RepeatedTest` or retry annotations spotted.
- **No mutation testing**, no contract tests (Pact/Spring Cloud Contract), no chaos/load tests.

---

## Critical gaps (would refuse to ship without)

1. **Payment idempotency is unverified.** The `idempotencyKey` column exists, the lookup logic exists, but no test proves a duplicate POST returns the same order instead of charging twice. Add `IdempotentOrderCreationIntegrationTest` against Testcontainers PG.
2. **Stripe HMAC signature verification is mocked away.** Write a test that uses Stripe's `Webhook.computeHmacSha256` to construct a real signed payload from a known secret, then passes it through `paymentService.handleWebhookEvent` without mocking `Webhook.constructEvent`. This is a 30-minute test that would catch a `getWebhookSecret()` regression that mocks would miss.
3. **Refund webhook (`charge.refunded`) is in the "ignore" branch.** Phase 17 just shipped vendor refunds via Stripe. Backend handling needs a test, otherwise a customer-initiated refund via Stripe dashboard will silently desync the order ledger.
4. **JWT edge cases at integration level.** Add a controller test that POSTs with an expired token, a token signed by the wrong issuer, and a token with the wrong audience. Currently we trust Spring Security defaults — fine, but one config mistake (e.g. audience validator disabled) and we have no canary.
5. **Playwright not in CI**. `e2e/` exists, runs locally, doesn't gate merges. Run it (at minimum `storefront-flows.spec.ts`) on PR against the docker-compose stack.

---

## Sneaky risks (tests that pass but shouldn't comfort you)

1. **`PaymentServiceTest` — `MockedStatic<Webhook>` makes the entire signature verification a no-op.** A green test with a wrong webhook secret config would still pass.
2. **`TestJWTMiddleware_Validate_ValidToken` openly admits it can't validate the token.** The test name promises something it does not deliver — comment on line 124 says "Note: Expected 401 due to JWKS validation, got %d" with `t.Logf`, not `t.Errorf`. This test passes regardless of outcome.
3. **`OrderStateMachineServiceTest.testThreadSafety`** is named "thread safety" but runs entirely sequentially. It does not actually exercise concurrency. Mislabelled — and a real concurrency bug would slip through.
4. **Java tests under `@WebMvcTest` use `addFilters = false`** (e.g. `PaymentControllerTest`), which bypasses `JwtTenantFilter` entirely. These tests prove controller-handler logic but not the security chain — a missing `@PreAuthorize` would not be caught here.
5. **No JaCoCo means the 432 `@Test` annotations could be hitting 40% of branches and we'd never know.** Volume metrics without coverage metrics are vanity.

---

## Top 5 highest-leverage tests to add tomorrow

1. **`PaymentWebhookSignatureIntegrationTest`** — real HMAC, real `Webhook.constructEvent`, asserts the wired secret is actually used. ~50 lines, catches the most embarrassing class of payment outage.
2. **`GuestOrderIdempotencyIntegrationTest`** — POST same `idempotencyKey` twice against Testcontainers, assert same `Order.id` returned and only one financial transaction. P0 for retry-safety.
3. **`JwtSecurityIntegrationTest`** — three tests: expired token → 401, wrong audience → 401, no token on a protected endpoint → 401. Use `MockMvc` with full security filter chain (no `addFilters=false`).
4. **`RefundWebhookHandlingIntegrationTest`** — Stripe `charge.refunded` event arrives, order's `paymentStatus` flips to `REFUNDED`, financial transaction reversal posted. Pairs with PR #51.
5. **Add JaCoCo to `core-java` and fail CI below 70% line coverage on `payment`, `security`, `order` packages.** This isn't a single test but it's the single most leveraged change — it forces all the above gaps to surface, and prevents the next one.

---

## Summary

The suite is bigger than claimed (595+ vs 516 invocations) and the *integration* tests on tenant isolation and RLS are genuinely excellent — the kind of tests I'd cite in a postmortem as having saved the day. But the **payment subsystem is the weak point**: signature verification, idempotency, and refund handling are all either mocked-through or absent. Frontend Jest coverage is thin and Playwright doesn't gate CI. Trust the suite to catch tenant-isolation regressions; do NOT trust it to catch a payment regression. Address the top-5 list above and the trust-score moves from 6 to 8.
