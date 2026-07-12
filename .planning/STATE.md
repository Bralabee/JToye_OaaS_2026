---
gsd_state_version: 1.0
milestone: v2.2
milestone_name: production-hardening-vendor-order-ops
status: milestone_complete
stopped_at: Milestone complete (Phase 19 was final phase)
last_updated: 2026-07-11T20:36:47.571Z
last_activity: 2026-07-11 -- Phase 19 closure plan 19-09 executing
progress:
  total_phases: 9
  completed_phases: 7
  total_plans: 31
  completed_plans: 33
  percent: 78
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-14)

**Core value:** Vendors can manage their business end-to-end — from marketing to kitchen fulfilment — through a single platform with real-time visibility, running safely on verified infrastructure that can scale past one replica.
**Current focus:** Milestone complete

## Current Position

Phase: 19
Plan: Not started
Status: Milestone complete
Last activity: 2026-07-12 - Completed quick task 260712-hnc: Keycloak deprovisioning on tenant offboard (#102 remainder)

Progress: [██████████] 100%

## Post-Milestone Activity (v2.2 → v2.3 gap)

- 2026-07-06 — Phase 17 merged (PR #57); k8s kustomize cleanup series (PRs #66-#69)
- 2026-07-07 — QA-council remediation merged (PR #70): KDS tenantId, shop-write IDORs (M3+ext), per-tenant cleanup job (M1), error codes (L1/L2), frontend deps (M4)
- 2026-07-07 — edge-go image pipeline restored (PR #72): Dockerfile golang 1.22→1.25 drift from #57 had broken every main image build since 2026-07-06
- 2026-07-07 — Observability stack restored after 7 weeks down (PR #73): Grafana port param, core-java scrape auth (2-layer), edge-go dead target removed, redis-exporter healthcheck
- 2026-07-07 — #71 RLS integration-suite CI enablement: integrationTest Gradle task + CI job, IntegrationTestSupport harness, 9 never-running classes repaired, NOSUPERUSER RLS-enforcement pattern, ShopImageCrossTenantIntegrationTest IDOR guard (+7 tests → 692)
- OPEN: #61 refund E2E — BLOCKED on Stripe test-mode keys (none in env; STRIPE_API_KEY empty in running container) + WR-09 product decision (single vs multiple partial refunds)

## Performance Metrics

**Velocity:**

- Total plans completed (M2): 10 + Milestone v2.2: 5
- Average duration: —
- Total execution time: — hours

**By Phase (milestone 2 history):**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1 | 1 | - | - |
| 2 | 1 | - | - |
| 3 | 2 | - | - |
| 4 | 1 | - | - |
| 5 | 1 | - | - |
| 6 | 1 | - | - |
| 7 | 1 | - | - |
| 8 | 2 | - | - |
| 11 | 3 | - | - |
| 17 | 4 | - | - |
| 18 | 7 | - | - |
| 19 | 9 | - | - |

**Milestone v2.2 (executing):**

| Phase | Plan | Duration | Tasks | Files | Tests added |
|-------|------|----------|-------|-------|-------------|
| 12    | 01   | ~90min   | 4     | 6     | 8 Java      |
| 12    | 02   | ~5min    | 6     | 7     | 8 Jest + 3 Playwright |
| 13    | 01   | ~45min   | 5     | 8     | 10 Java (6 integration + 4 unit) |
| 14    | 01   | ~20min   | 5     | 17    | 8 Java (5 StockService unit + 1 Concurrent integration + 2 StockDecrementLocation + 1 Handler + 2 refactored OrderService) |
| 14    | 02   | ~40min   | 3     | 12    | 6 Java (1 Golden-file + 1 QueryPlan + 1 QueryCount + 1 CrossTenant + 2 rewritten GetSummary) + committed 1k-row JSON fixture |
| 15    | 01   | ~60min   | 6     | 14    | Offline validator (k8s/scripts/validate-networkpolicies.py: 6 manifests, 13 podSelector refs resolved against workload labels). No code-level tests — phase is infra-docs-only. |
| 16    | 01   | ~2h      | 5     | 13    | 4 Go (TestOpenAPISpec_IsValidJSON + TestOpenAPISpec_AllRoutesDocumented + TestOpenAPISpec_HasSecurityDefinition + TestOpenAPISpec_Fresh). Plus npm validator gate in CI. |

**Recent Trend:**

- Last plan: 16.1-06 Phase 16.1 closure (admin/metadata) — 4 file edits (REQUIREMENTS.md AUDIT-W0-01..05 registration; CHANGELOG.md Phase 16.1 [Unreleased] entry; ROADMAP.md Phase 16.1 entry marked 6/6 complete + Progress table row; STATE.md Current Position advances to Phase 17). 0 source/test changes. AUDIT-W0-01..05 retrospectively added to the requirements ledger; coverage block updated 11 → 16. Closes Phase 16.1 administratively.
- Trend: milestone v2.2 execution continues green; phases 13, 14, 16, and 16.1 complete (drafting), 15 implementation-complete, 12 operationally complete. Phase 16.1 branch feature/phase-16.1-pre-prod-hardening has 12 atomic commits (6 plans × 2 commits each on average) closing the 5 council-audit Wave-0 blockers. Branches ready for PR: feature/phase-13-guest-tracking-tenant-validation, feature/phase-14-stock-race-summary-aggregation, feature/phase-15-k8s-networkpolicies-sealed-secrets, feature/phase-16-go-edge-openapi, feature/phase-16.1-pre-prod-hardening. Phase 12 Task 12-02-07 staging-observation gate still pending. Only Phase 17 (vendor order detail + Stripe refund) remains to close out v2.2.

*Updated after each plan completion*
| Phase 16.1 P01 | 2min | 1 tasks | 1 files |
| Phase 16.1 P02 | 4min | 2 tasks | 3 files |
| Phase 16.1 P03 | 4min | 1 tasks | 3 files |
| Phase 16.1 P04 | 10min | 2 tasks | 3 files |
| Phase 16.1 P05 | 22min | 2 tasks | 2 files |
| Phase 16.1 P06 | ~5min | 3 tasks | 4 files |

## Accumulated Context

### Roadmap Evolution

- Phase 16.1 inserted after Phase 16: Pre-prod Hardening — Wave 0 council audit fixes (5 confirmed pre-prod blockers): OrderSseService cross-tenant leak, Customer-orders IDOR, Stripe webhook idempotency, reviews_tenant_write RLS rewrite, FORCE RLS on 9 tables. Must land before Phase 17 Stripe refund work. (URGENT)
- Phase 16.1 (Pre-prod Hardening) — DONE 2026-04-28. 5 council-audit blockers closed (cross-tenant SSE leak, /public/orders IDOR, Stripe webhook idempotency, reviews_tenant_write RLS rewrite, FORCE RLS on 9 tables). V35 migration ships them atomically. RlsContractTest is a permanent CI guard against future RLS drift. AUDIT-W0-01..05 retrospectively registered in REQUIREMENTS.md (16-entry total, traceability complete).

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [M2 Roadmap]: API versioning first — changes every URL, doing later means double rework
- [M2 Roadmap]: KDS split into 3 phases (security, pipeline, UI) — highest complexity feature, security must be proven before UI
- [M2 Roadmap]: Test coverage has no dependencies — can parallel any phase
- [M3 Scope]: Work Orders A+B+C only — A ships in 2 days as a safety net, B/C each ~1 week. Deferring D–O to keep the milestone bounded at ~2.5 weeks
- [M3 Scope]: Skip research — state-of-codebase doc is already research-grade with file:line evidence; phase-level research will cover framework-specific pitfalls (StompBrokerRelay, Alertmanager)
- [M3 Scope]: STOMP broker behind `stomp.broker.mode` config flag — keeps local dev on in-memory, staging/prod on RabbitMQ relay
- [M3 Roadmap]: Phase 9 (SECR) ships first as standalone safety net — no dependencies, 2 days, closes credential-exposure hole before B/C start
- [M3 Roadmap]: Phase 10 (STFR) is independent of 9 and 11 — can run in parallel with either
- [M3 Roadmap]: Phase 11 (STMP) depends on Phase 9 — STMP-05 reuses the Alertmanager + Slack route from SECR-04/SECR-05
- [M3 Roadmap]: One phase per work order (no splitting) — task breakdown fits cleanly, preserves audit traceability
- [Phase 16.1]: Bundled three audit-finding fixes (AUDIT-W0-03 Stripe idempotency, AUDIT-W0-04 reviews_tenant_write rewrite, AUDIT-W0-05 FORCE RLS on 9 tables) into a single V35 Flyway migration. — Partial application would leave the DB in an unsafe state where idempotency exists but FORCE RLS does not. Atomic deploy is required per phase 16.1 LOCKED CONTEXT decisions.
- [Phase 16.1]: Fail-closed at OrderSseService.subscribe() — throw IllegalStateException when TenantContext is unset, rather than silently attaching a tenant-less emitter — LOCKED in 16.1-CONTEXT.md Item 1: silent fallback to a default bucket would mask a misconfigured request pipeline (JwtTenantFilter not populating context) and could re-introduce the cross-tenant leak this plan exists to close.
- [Phase 16.1]: Filter SSE broadcasts at the service layer (per-tenant ConcurrentHashMap routed by event.tenantId()), not via @PreAuthorize on OrderController — Broadcasts run on the RabbitMQ consumer thread off-request — Spring SecurityContext is not propagated there, so controller-level annotation cannot enforce tenant scoping at broadcast time. Filtering inside OrderSseService is the correct layer.
- [Phase 16.1]: AUDIT-W0-02 closed: GET /public/orders requires mandatory verify order-number; trackOrder runs unconditionally as proof-of-ownership — LOCKED in 16.1-CONTEXT.md Item 2; the prior optional verify allowed trivial enumeration of any customer's order history by email
- [Phase 16.1]: GlobalExceptionHandler now preserves controller-thrown ResponseStatusException + maps MissingServletRequestParameterException to 400 — Auto-fix Rule 1/2 deviation during 16.1-03 — without these handlers the catch-all Exception matcher swallowed both as 500, masking the LOCKED 400 contract
- [Phase ?]: [Phase 16.1-04]: Stripe webhook idempotency guard sits INSIDE the existing @Transactional boundary (not REQUIRES_NEW) — semantic is 'processed at least once', so a downstream throw rolls back the dedup row and Stripe's retry succeeds cleanly. REQUIRES_NEW path requires a separate failed-event reconciliation flow which is out of scope for Wave 0.
- [Phase ?]: [Phase 16.1-04]: TOCTOU-safe single-statement INSERT ... ON CONFLICT DO NOTHING via JdbcTemplate, NOT a JPA ProcessedStripeEvent entity + repo with existsByEventId+saveAndFlush — the JPA pattern has a SELECT-then-INSERT race window under concurrent webhook delivery from Stripe's edge.
- [Phase ?]: [Phase 16.1-05]: Drop SUPERUSER via SET LOCAL ROLE rls_test_role in Testcontainers RLS-denial tests — postgres:15 testcontainer creates the test user as SUPERUSER which bypasses RLS regardless of NOBYPASSRLS / FORCE; provisioning a dedicated NOSUPERUSER NOBYPASSRLS LOGIN role and SET LOCAL ROLE-ing to it for each RLS-sensitive test transaction is the canonical pattern.
- [Phase ?]: [Phase 16.1-05]: EXEMPT_TABLES list expanded to 4 entries (flyway_schema_history, processed_stripe_events, tenants, revinfo) — tenants and revinfo are infrastructure tables with no tenant column and were caught by the schema-walk; each carries a written code-comment justification per the LOCKED 'add with justification' rule.
- [Phase ?]: [Phase 16.1-05]: Storefront review-submit wiring confirmed correct as-is — ReviewService.createReview sets TenantContext, TenantSetLocalAspect translates to set_config('app.current_tenant_id', ?, true), and the V35 policy's app branch fires. The customer-email branch exists for defense-in-depth and is exercised in the test only. No production code change required.

### Pending Todos

- **Plan 12-02 Task 07 manual gate (human-verify):** after ≥1-week staging observation of Report-Only CSP, flip header key in `frontend/next.config.mjs` from `Content-Security-Policy-Report-Only` to `Content-Security-Policy` (enforce), regenerate header snapshot via `npm test -- __tests__/header-snapshot.test.ts -u`, commit both files in one PR. Verification steps (Stripe 3DS, NextAuth signin, CSP-no-violations Playwright spec against staging) documented in 12-02-PLAN.md Task 07 + 12-02-SUMMARY.md
- Backfill `status: complete` frontmatter on the 5 quick-task SUMMARY.md files (Deferred Items below) during an early v2.2 housekeeping pass
- Commit `frontend/.env.local.example` placeholder hardening change (block-secrets hook prevents Claude from staging it — needs a manual commit outside Claude)
- Advance to next Phase 13+ plan now that Phase 12 operational work (both plans) is complete
- **Phase 15 cluster-admin rollout (4 steps):** (1) `helm install sealed-secrets-controller sealed-secrets/sealed-secrets -n kube-system`; (2) `kubeseal --fetch-cert > k8s/certs/<env>/sealed-secrets-pub.pem` per cluster; (3) `./k8s/scripts/seal-secrets.sh --cert <cert> --namespace jtoye-production --input <plaintext> --output k8s/production/sealed-secrets/`; (4) `kubectl apply -k k8s/staging/` + functional verification (frontend cannot nc postgres, frontend can wget core-java). Full details: `.planning/phases/15-k8s-networkpolicies-sealed-secrets/15-01-SUMMARY.md` + `docs/runbooks/sealed-secrets.md`.

### Blockers/Concerns

- Port conflicts in dev env (frontend 3100 because MCP server holds 3000; Postgres 5432 shared with unrelated `dealflow_*` containers) — E2E smoke tests may need those containers stopped first
- Stripe refund API (VOPS-02) requires phase-level research into idempotency keys + webhook `charge.refunded` handling — treat as a design-gate before writing the controller
- K8s Sealed Secrets (INF-02) requires an operator install in the cluster + key rotation policy — not just a manifest change

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260708-bu6 | Create docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md — prioritized remediation backlog from the 2026-07-08 enterprise-readiness audit | 2026-07-08 | fe43427 | [260708-bu6-create-docs-analysis-remediation-backlog](./quick/260708-bu6-create-docs-analysis-remediation-backlog/) |
| 260708-g8c | Issue #79 P0-3: untrack/relocate 147 db dumps off-tree, pii-guard CI, UK GDPR Art 33/34 exposure assessment (history rewrite handled post-merge) | 2026-07-08 | d162e82 | [260708-g8c-issue-79-p0-3-purge-pii-dumps-from-git-h](./quick/260708-g8c-issue-79-p0-3-purge-pii-dumps-from-git-h/) |
| 260708-jj1 | Issue #77 P0-1: add frontend /api/health route (+2 Jest tests), align compose healthcheck; verified live — curl 200 {"status":"ok"} unauthenticated, container healthy under new probe | 2026-07-08 | 9712eb2 | [260708-jj1-issue-77-p0-1-add-frontend-api-health-ro](./quick/260708-jj1-issue-77-p0-1-add-frontend-api-health-ro/) |
| 260708-jzm | Issue #78 P0-2: k8s production→prod, ActiveProfileValidator fail-fast (+6 tests), application-dev.yml, Dockerfile baked -Dspring.profiles.active override removed; container-proven (production→hard fail, prod→boots, live dev healthy) | 2026-07-08 | a389739 | [260708-jzm-issue-78-p0-2-fix-prod-profile-mismatch-](./quick/260708-jzm-issue-78-p0-2-fix-prod-profile-mismatch-/) |
| 260708-l2c | Issue #80 P0-4: rotate all committed Keycloak/MinIO creds; realm-export → template + envsubst sidecar (KeyProvider material + PBKDF2 user hashes stripped), :?-required compose vars, verify-env.sh deny-list wired into start-dev.sh; live-proven — old secret 401, rotated grant 200 → API 200, Playwright SSO login green, app DB untouched | 2026-07-08 | 81035e2 | [260708-l2c-issue-80-p0-4-rotate-committed-keycloak-](./quick/260708-l2c-issue-80-p0-4-rotate-committed-keycloak-/) |
| 260708-mow | Issue #81 P0-5: VAT ledger correctness (VALIDATE) — HMRC fraction method net-of-gross (single VatCalculator used by entity + JPQL), per-product vat_rate (V40) + predominant-liability delivery, idempotent single ledger entry (partial unique index, race-safe), V40 in-place backfill+dedupe+audit note; +VatCalculatorTest/LedgerSingleEntryIntegrationTest, golden regen; verified 10/10, live DB V40 zero dup ledger rows | 2026-07-08 | 0a0217c | [260708-mow-issue-81-p0-5-vat-ledger-correctness-fra](./quick/260708-mow-issue-81-p0-5-vat-ledger-correctness-fra/) |
| 260708-ovt | Issue #82 P0-6: PPDS/Natasha's-Law label (VALIDATE) — inline allergen emphasis via IngredientMarkupParser (fail-soft, render-time authoritative + allergen_spans cache), V41 shelf_life_days/durability_type/allergen_spans, computed Use-by/Best-before, tenant-scoped business identity, fail-loud IncompleteLabelDataException→422 (no non-compliant PDF), removed CONTAINS block + 'No allergens declared' fallback; AC3 golden-file test + docs/ppds-label-markup.md; frontend 'mark allergens' editor deferred to fast-follow; verified 7/7, live V41 | 2026-07-08 | dc56b37 | [260708-ovt-issue-82-p0-6-ppds-natasha-s-law-label-i](./quick/260708-ovt-issue-82-p0-6-ppds-natasha-s-law-label-i/) |
| 260708-rlp | Issue #83 P1-1: RBAC — KeycloakRealmRoleConverter (realm_access.roles→ROLE_*) + @EnableMethodSecurity, @PreAuthorize("hasRole('admin')") on refunds/finance/GDPR/dev-admin; +3 converter unit tests +6 RoleBasedAccessIntegrationTest (low-priv 403 on refund/finance/GDPR, admin allowed); full test+integrationTest green (96/96), docs-freshness synced at 735 | 2026-07-08 | 2dfce74 | [260708-rlp-implement-issue-83-p1-1-rbac-method-secu](./quick/260708-rlp-implement-issue-83-p1-1-rbac-method-secu/) |
| 260708-ses | Issue #84 P1-2: GDPR erasure completeness — guest-order email sweep (reaches customer_id NULL rows), Envers orders_aud/customers_aud PII scrub via tenant-scoped native UPDATEs + V42 RLS UPDATE policies (were SELECT+INSERT only → scrub silently denied), S3 review-photo deletion via StorageService, durable PII-free erasure_records table (SHA-256 email hash, FORCE RLS); TDD, +GdprErasureIntegrationTest guest-PII reachability proof; full local test+integrationTest BUILD SUCCESSFUL, docs-freshness 736/V42 | 2026-07-08 | a11348c | [260708-ses-implement-issue-84-p1-2-gdpr-erasure-dep](./quick/260708-ses-implement-issue-84-p1-2-gdpr-erasure-dep/) |
| 260708-teb | Issue #85 P1-3: guest-checkout stock — VERIFY-FIRST characterization test empirically CONFIRMED the double-decrement (qty=3, stock 10→4 pre-fix), then converged to a single retry-safe decrement at CONFIRM via StockService (removed eager naked read-modify-write in PublicStorefrontService.createGuestOrder; TOCTOU 500 gone; cancel-path restock symmetry preserved); +GuestCheckoutStockConvergenceIntegrationTest (A delta 1×qty=7, B concurrent last-unit no-500) + StockDecrementLocationTest guard; no schema change; full gate BUILD SUCCESSFUL (8m49s), docs-freshness 739. Executor hit account session limit mid-gate → recovered inline | 2026-07-08 | 8ffdf1f | [260708-teb-implement-issue-85-p1-3-guest-checkout-s](./quick/260708-teb-implement-issue-85-p1-3-guest-checkout-s/) |
| 260708-tsl | Issue #86 P1-4: Redis resilience — RedisCacheErrorHandler (CachingConfigurer) degrades cache GET/PUT (WARN) + EVICT/CLEAR (ERROR, staleness-alarmed) to source-of-truth so a Redis blip is a cache miss not a 500; explicit Lettuce command timeout on the rate-limit client sourced from per-profile spring.data.redis.timeout (2000/3000/2500ms — no hardcoded literal) replacing the 60s default; RateLimitInterceptor wraps the Redis section in try/catch → fail-open-with-alarm (jtoye.ratelimit.fail_open counter) preserving the genuine 429 path; jtoye.cache.errors metric; +RedisFaultInjectionIntegrationTest (Testcontainers, redis.stop() mid-test) +RedisCacheErrorHandlerTest +RateLimitInterceptorFailOpenTest; no schema change; full gate green (447 unit + 100 integration, 9m35s), docs-freshness 746 | 2026-07-08 | fd5c193 | [260708-tsl-implement-issue-86-p1-4-redis-resilience](./quick/260708-tsl-implement-issue-86-p1-4-redis-resilience/) |
| 260709-bl2 | Issue #87 P1-5: JWT audience + realm hardening + session refresh-token leak (all 3 tiers) — core-java AudienceValidator wired additively via DelegatingOAuth2TokenValidator(createDefaultWithIssuer + AudienceValidator), fail-closed on blank config, expected-audience env-injected (jtoye.security.jwt.expected-audience:${JWT_EXPECTED_AUDIENCE:core-api}); ALSO strengthens issuer (custom decoder was timestamp-only); #83 role converter untouched; edge-go audience now fail-closed (defaultJWTAudience core-api, check unconditional, runs before tenant check) + jwt_test table cases; realm template bruteForceProtected=true + passwordPolicy len(12)+classes+notUsername + core-api oidc-audience-mapper; frontend refreshToken removed from client Session (buildSession pure fn, deleted defensively + dropped from Session type) kept on server JWT; +AudienceValidatorTest +session-callback.test; 3-suite gate green (452 unit + 100 integration + edge-go 6 pkgs/75 funcs + 104 Jest), docs-freshness 755. Post-merge CI caught a tsc-only weak-type error jest missed (buildSession param typed as JWT, fix cebacdf). RUNTIME FOLLOW-UP: realm needs Keycloak DB-drop + re-import to emit aud=core-api (live tokens fail-closed until then) | 2026-07-09 | 074a639 | [260709-bl2-implement-issue-87-p1-5-jwt-audience-val](./quick/260709-bl2-implement-issue-87-p1-5-jwt-audience-val/) |
| 260709-iro | Issue #88 P1-6: public-path rate limiting — closes the tenant-less bypass where RateLimitInterceptor returned true when TenantContext absent (every /public/** guest path). New IP-keyed public bucket branch runs before tenant logic; ClientIpResolver (XFF-first-hop, getRemoteAddr fallback, "unknown" sentinel, spoofing caveat documented); distinct rl:public:{ip} Redis namespace so public floods never touch tenant buckets; 429 + Retry-After + generic body (no tenantId leak); limits env-injected rate-limiting.public.requests-per-minute/burst/window-seconds (base ${}-overridable, prod literals); runs INSIDE #86 fail-open try/catch + jtoye.ratelimit.fail_open counter; +ClientIpResolverTest(7) +RateLimitInterceptorTest(+public cases,13) +PublicRateLimitIntegrationTest (Testcontainers real Redis: flood→429+Retry-After, tenant req unaffected, fail-open on redis.stop()→200); core-java-only; full gate green (463 unit + 101 integration, 9m33s), docs-freshness 767 | 2026-07-09 | d8254dd | [260709-iro-implement-issue-88-p1-6-ip-session-keyed](./quick/260709-iro-implement-issue-88-p1-6-ip-session-keyed/) |
| 260710-qeq | Issue #91 P1-9: supply-chain gate — Trivy fs+image scans now gate the build (exit-code:'1' + ignore-unfixed:true, SARIF upload preserved via if:always()); Snyk drops continue-on-error → real HIGH+ gate guarded by if env.SNYK_TOKEN!='' (job-level env) so an unconfigured secret skips-not-fails; all 11 third-party actions (trivy×2, snyk, docker×4, azure×2, slack×2) pinned @master/@vN → 40-char commit SHAs w/ # vX comments (actions/* + codeql left for Dependabot); new .github/dependabot.yml v2 covering gradle(/,/core-java) + gomod(/edge-go) + npm(/frontend) + docker(×3) + github-actions(/), weekly, grouped minor+patch, limit 5. FIX 05cb93b: split Trivy into sarif-report + table-gate steps because format:sarif forces an all-severities scan (single-step exit-code gated on LOW/MED too — first CI run red on non-critical gomod/npm advisories); gate now scoped to fixable CRITICAL,HIGH + prints CVEs. AC#1 EMPIRICALLY PROVEN on PR #140 both directions: gate failed build on findings (red), then passed (green 35s) once scoped → current tree has no fixable CRITICAL/HIGH. MERGED #140 → main 8f99b15, #91 CLOSED | 2026-07-10 | 05cb93b | [260710-qeq-gate-ci-on-trivy-snyk-criticals-dependab](./quick/260710-qeq-gate-ci-on-trivy-snyk-criticals-dependab/) |
| 260710-s6d | Issue #89 P1-7: CSP enforce + drop script-src 'unsafe-inline' via nonce. Moved CSP from static next.config headers() into middleware.ts (per-request nonce + 'strict-dynamic', canonical Next recipe: x-nonce+CSP on request so Next stamps its scripts, enforcing by default, CSP_REPORT_ONLY opt-out) wrapped in NextAuth auth (matcher broadened; /dashboard still gated server-side, no authorized callback). New lib/security-headers.ts buildCsp() (testable, no unsafe-inline in script-src, style-src unsafe-inline kept per AC, upgrade-insecure-requests gated behind CSP_UPGRADE_INSECURE_REQUESTS off-by-default so local http+MinIO images work). CRITICAL live-E2E find: nonce can't reach statically-prerendered pages → their inline/chunk scripts blocked (homepage+dashboard failed first run); fixed via app/layout.tsx export const dynamic='force-dynamic' (app already mostly dynamic — only auth/utility pages were static). Reworked csp-headers.test + header-snapshot (+snap regen); metrics 105→109 jest / 771→775 total; CLAUDE.md count synced. PROOF: jest 108 green, build green (27 routes ƒ), curl shows enforcing CSP w/ nonce no unsafe-inline, Playwright csp-no-violations 6/6 green, storefront screenshot renders (11 imgs, 0 violations). Stripe 3DS verified by config only (allowlists intact, no live card). MERGED #166 → main 59cb1d4, #89 CLOSED | 2026-07-10 | 59cb1d4 | [260710-s6d-enforce-csp-by-default-drop-script-src-u](./quick/260710-s6d-enforce-csp-by-default-drop-script-src-u/) |
| 260710-u1q | Issue #90 P1-8: k8s backup hardening. Root causes all confirmed: postgres:15-alpine busybox (GNU date -d/grep -oP fail under set -e), runtime apk add aws-cli (default-deny NetworkPolicy blocks), no BYPASSRLS role (FORCE RLS → app-role dump captures 0 tenant rows), CronJob referenced NON-EXISTENT resources (jtoye-secrets/jtoye-config vs real postgres-credentials/app-config → pod never started). Fix: new infra/backups/Dockerfile (postgres:15-bookworm + aws-cli + GNU baked) running new hardened infra/backups/k8s-backup.sh (custom-format, fail-loud, size-floor + pg_restore --list verify, empty-bucket-safe prune); infra/backups/create-backup-role.sql (least-priv jtoye_backup BYPASSRLS, SELECT tables+SEQUENCES — sequence grant added after live test caught revinfo_seq perm error); rewired cronjob to real secret/configmap + backup role; added backup-username/password + s3-backup-credentials + s3.backup.* keys (REPLACE_WITH placeholders). PROVEN LOCALLY end-to-end vs live pg+MinIO: AC#2 app-role=0/BYPASSRLS=25 products; AC#1 image runs exit0, 133KiB dump→MinIO S3; AC#3 seeded 2025 object PRUNED (job exit0); AC#4 restore drill S3→pg_restore scratch DB ~5s RTO, restored products=25/orders=57/customers=4/shops=10, RPO≤24h. kubectl kustomize builds (27 res), all env refs resolve, bash -n clean. PENDING (no cluster — AKS unreachable): in-cluster exit0 to PROD S3 + prod restore drill; image needs build+push to registry (not in CI). PR pending | 2026-07-10 | (pending) | [260710-u1q-harden-k8s-pg-backup-cronjob-bypassrls-d](./quick/260710-u1q-harden-k8s-pg-backup-cronjob-bypassrls-d/) |
| 260711-bej | Two USER decisions recorded + code: (1) #178 item 1 auto-approve stance = HYBRID BY MODEL — new onboarding.auto-approve-models (default [WHITE_LABEL]) + OnboardingProperties.autoApprovesModel(); GateChainRunner fires APPROVE on global force-on OR per-model policy (two external calls preserve @SpyBean E2E path); WHITE_LABEL auto-approves on green gates, MARKETPLACE parks at PENDING_APPROVAL (admin queue = #178 slice 2). (2) #102 Stripe = CONNECT KEYED TO MODEL — destination charges MARKETPLACE / direct charges + app fee WHITE_LABEL, destination first in future phase, decision-only (no Stripe code). ADR-0001 seeded (docs/architecture/decisions/), state-model §9 item 1 DECIDED, 18-HUMAN-UAT item 5 PASS (5/5), decision comments on #178+#102 (both stay open). Tests 918→921 (693 Java @Test), docs-freshness green, no migration (V43) | 2026-07-11 | d936d6e | [260711-bej-record-onboarding-auto-approve-stripe-co](./quick/260711-bej-record-onboarding-auto-approve-stripe-co/) |
| 260711-u22 | Fix image uploader: compress BEFORE size gate (preflight 50MB browser cap → canvas compress 1600px/0.85 → enforce 5MB server cap; non-transparent PNG→JPEG quality ladder 0.85/0.75/0.65; GIF-only 5MB hard limit; honest error copy). 13 new jest tests (190 total), metrics 988→1001 | 2026-07-11 | e6b202e | [260711-u22-fix-image-uploader-compress-before-size-](./quick/260711-u22-fix-image-uploader-compress-before-size-/) |
| 260712-hnc | Issue #102 remainder: Keycloak deprovisioning on offboard — first Java-side Keycloak admin integration (KeycloakAdminClient RestClient seam + KeycloakDeprovisionService, REQUIRES_NEW inside afterCommit so a plain write isn't silently lost in the committed tx), V49 keycloak_deprovisioned_at marker (stamped only on full success), best-effort non-rolling-back offboard hook, admin-gated OFFBOARDED-only idempotent re-trigger POST /api/v1/admin/tenants/{id}/keycloak/deprovision, env + all-overlay k8s wiring, INERT by default (WARN no-op + RFC 7807 400 not-configured). Tests 1166→1181 (+15 Java), OpenAPI snapshot regenerated, docs-freshness green. Live E2E vs real Keycloak + PR: orchestrator follow-up | 2026-07-12 | 97753d9 | [260712-hnc-keycloak-deprovisioning-on-tenant-offboa](./quick/260712-hnc-keycloak-deprovisioning-on-tenant-offboa/) |

## Deferred Items

Items acknowledged and deferred at milestone v2.1 close on 2026-04-18:

| Category | Item | Status |
|----------|------|--------|
| quick_task | 260414-fe3-frontend-security-and-tests | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |
| quick_task | 260414-inf-infrastructure-hardening | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |
| quick_task | 260414-j9c-edge-go-security-hardening-batch-phase-1 | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |
| quick_task | 260414-jkp-java-core-data-integrity-batch-phase-2-o | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |
| quick_task | 260414-ltc-low-touch-cleanup | Complete (shipped PR #40); no frontmatter status field — tool reports "unknown" |

All 5 are deep-audit P1 quick tasks that shipped in PR #40 on 2026-04-16. Work is done; only tooling metadata is missing. Consider adding `status: complete` frontmatter during v2.2 planning cleanup.

## Session Continuity

Last session: 2026-07-11 — Phase 19 closure (19-09) Task 2 full gate green; awaiting human UAT
Stopped at: 19-09 Task 3 — human whole-app browser UAT of the 6 ROADMAP success criteria (blocking human-verify gate)
Resume file: .planning/phases/19-full-frontend-experience-overhaul/19-09-SUMMARY.md
