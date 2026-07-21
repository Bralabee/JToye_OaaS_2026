---
phase: 23
slug: vendor-scoped-access-responsive-dashboard-nav
status: validated
nyquist_compliant: true
wave_0_complete: true
created: 2026-07-19
updated: 2026-07-21
---

# Phase 23 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `23-RESEARCH.md` §9 (Validation Architecture) + Security Domain.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (Java)** | JUnit 5 + Spring Boot Test + Testcontainers 1.21.3 (real Postgres 15 for RLS) |
| **Framework (Frontend)** | Jest 29.7.0 + @testing-library/react; Playwright 1.59.1 (e2e) |
| **Config file** | `core-java/build.gradle` (`test` + `integrationTest`, `@Tag("testcontainers")`); `frontend/jest.config` |
| **Quick run (Java)** | `./gradlew test --tests "uk.jtoye.core.security.ShopStaff*"` |
| **Quick run (Frontend)** | `cd frontend && npx jest components/dashboard` |
| **Frontend typecheck** | `cd frontend && npm run build` (tsc — NOT covered by jest; see `feedback_frontend_typecheck_gate`) |
| **375px e2e** | `cd frontend && npx playwright test --project=mobile dashboard-mobile.spec` (add 375px case) |
| **Full suite command** | `./gradlew test integrationTest` + `cd frontend && npm run build && npx jest` + `npx playwright test` |
| **Count gate** | `scripts/docs-freshness.sh --write` (reconciled at phase gate 23-15 to total 1573 / schema 57; enforced by `.github/workflows/docs-freshness.yml`) |
| **Estimated runtime** | Java integration ~few min (Testcontainers Postgres); Jest ~30s; Playwright mobile ~1–2 min |

---

## Sampling Rate

- **After every task commit:** Run the single new suite for that task (quick run).
- **After every plan wave:** Run `./gradlew test integrationTest` + `cd frontend && npm run build && npx jest`.
- **Before `/gsd:verify-work`:** Full Java suite + `npx playwright test` green + `scripts/docs-freshness.sh --write` reconciled.
- **Max feedback latency:** quick run < ~60s (single suite).

---

## Per-Requirement Verification Map

> Task IDs assigned at planning; this maps each phase requirement to its validation strategy (from RESEARCH §9).

| Requirement | Behavior | Test Type | Automated Command | File | Status |
|-------------|----------|-----------|-------------------|------|--------|
| VSA-01 | `shop_staff`/`_aud`/`user_directory` RLS+FORCE proven cross-tenant under NOSUPERUSER | integration (Testcontainers) | `./gradlew test --tests "*ShopStaffRlsPolicyIntegrationTest"` | ❌ W0: `ShopStaffRlsPolicyIntegrationTest.java` (copy `WebhookSubscriptionRlsPolicyIntegrationTest`) | ✅ green |
| VSA-01 | `user_directory` email PII hidden cross-tenant (FORCE load-bearing) | integration | same suite | ❌ W0 | ✅ green |
| VSA-01 | JIT-provision idempotent (concurrent first-requests → 1 GROUP_ADMIN row) | integration | `./gradlew test --tests "*ShopAccessJitProvisionTest"` | ❌ W0: `ShopAccessJitProvisionTest.java` | ✅ green |
| VSA-01 | Contract sweeps green (RLS+FORCE, no raw `::uuid` cast) | integration | `./gradlew test --tests "*RlsContractTest"` | ✅ exists (auto-covers new tables) | ✅ green |
| VSA-02 | SHOP_MANAGER on shop A → typed 403 (distinct `type`) on shop B write | integration | `./gradlew test --tests "*ShopAccessEnforcementIntegrationTest"` | ❌ W0 | ✅ green |
| VSA-02 | STAFF read-only: can transition order state, denied catalogue write | integration | same suite | ❌ W0 | ✅ green |
| VSA-02 | Read-scoping: scoped user's list returns only granted shops | integration | same suite | ❌ W0 | ✅ green |
| VSA-02 | 403 body `type` ≠ RLS 404 `type` (provably distinct) | integration | same suite | ❌ W0 | ✅ green |
| VSA-03 | Switcher persists selection (localStorage); non-GA cannot see "apply to all" | unit (Jest) | `cd frontend && npx jest components/dashboard` | ❌ W0 | ✅ green |
| VSA-03 | Shop-scoped screens react to the selected shop: products+orders lists narrow, create-form shop defaults/constrains (D-08) | unit (Jest) | `cd frontend && npx jest hooks/use-shop-context products-orders-shop-scope marketing-kitchen-shop-scope` | ❌ W0 (plan 23-07) | ✅ green |
| VSA-04 | list/grant/revoke; grant→access, revoke→403; last-GROUP_ADMIN→409 | unit (Jest) + integration | `npx jest` + `./gradlew test --tests "*StaffManagementIntegrationTest"` | ❌ W0 | ✅ green |
| MOBL-01 | 375px: sidebar hidden, tab bar visible, no occlusion/overflow | e2e (Playwright) + unit (Jest) | `npx playwright test --project=mobile dashboard-mobile.spec` | ⚠️ 390px exists — add 375px case | ✅ green (Jest 375px + satisfied-by-prior-work 23-05); ⚠️ live Playwright env-deferred |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Gap-Closure Proofs (plans 23-08..23-16)

> The phase's REVIEW + VERIFICATION gates found gaps after the original build (23-01..23-07);
> plans 23-08..23-14 closed them, and 23-16 migrated 7 legacy `@WithMockUser`/non-UUID-JWT test
> classes so the full `:core-java:integrationTest` task is genuinely green (331 tests, 0 failed).
> Each proof below is an automated command run over that green suite (Task 2 basis, 2026-07-21).

| Plan | Requirement | Proof (behaviour) | Automated Command | File | Status |
|------|-------------|-------------------|-------------------|------|--------|
| 23-08 | VSA-02 / VSA-04 | fail-closed: non-UUID-subject / anonymous / non-`Jwt` principal denied on all staff endpoints; `require(null,…)` typed 403 not NPE (CR-03 / CR-04) | `./gradlew :core-java:integrationTest --tests "*ShopAccessFailClosedIntegrationTest"` | `.../security/access/ShopAccessFailClosedIntegrationTest.java` | ✅ green (7/7, RED pre-fix 5/7) |
| 23-09 | VSA-04 | different-role re-grant APPLIES + is Envers-audited; concurrent revokes serialized (PESSIMISTIC_WRITE) and cannot empty the tenant's last GROUP_ADMIN (CR-05 / CR-06) | `./gradlew :core-java:integrationTest --tests "*StaffManagementIntegrationTest"` | `.../security/access/StaffManagementIntegrationTest.java` | ✅ green (19/19) |
| 23-10 | VSA-02 | a warm per-tenant read cache does NOT bypass the shop gate (cross-shop read denied) with caching genuinely enabled; null-shop reads restored (WR-08); CSV shop_id → per-row 400 not 403 (WR-07) (CR-01) | `./gradlew :core-java:integrationTest --tests "*ShopAccessCacheBypassIntegrationTest"` | `.../security/access/ShopAccessCacheBypassIntegrationTest.java` | ✅ green (5/5, RED pre-fix 2/5) |
| 23-11 | VSA-02 | STOMP KDS subscribe is shop-gated at SUBSCRIBE from explicit params (never SecurityContext); day-one implicit GROUP_ADMIN still permitted (CR-02) | `./gradlew :core-java:test --tests "*TenantChannelInterceptorTest"` | `.../websocket/TenantChannelInterceptorTest.java` | ✅ green |
| 23-12 | VSA-04 | grant input validation (foreign-tenant == unknown-user 404, no oracle); `GET /api/v1/staff/me` server-authoritative; directory PII masked + tenant-scoped erase (WR-05 / CR-08 backend / WR-10) | `./gradlew :core-java:integrationTest --tests "*StaffManagementIntegrationTest" --tests "*GdprErasureIntegrationTest"` | `.../security/access/StaffManagementIntegrationTest.java`, `.../gdpr/GdprErasureIntegrationTest.java` | ✅ green |
| 23-13 | VSA-03 / VSA-04 | frontend consumes server authority (`/me` MyAccessDto); no silent shop-pinning; sub-based `isSelf` identity (CR-08 frontend) | `cd frontend && npx jest components/dashboard app/dashboard hooks && npm run build` | `frontend/app/dashboard/__tests__/`, `frontend/hooks/__tests__/`, `frontend/components/dashboard/__tests__/` | ✅ green (jest 360/360, tsc build clean) |
| 23-14 | VSA-02 | enabling `strict-scoping` genuinely tightens — JIT tenant-wide GROUP_ADMIN de-honoured, operator grants + realm admins honoured, oldest-JIT bootstrap retained; membership cache real (proxy) + post-commit eviction proven (CR-07 / WR-01 / WR-11 / WR-09) | `./gradlew :core-java:integrationTest --tests "*StrictScopingTighteningIntegrationTest"` | `.../security/access/StrictScopingTighteningIntegrationTest.java` | ✅ green (5/5, RED pre-fix 4/5) |
| 23-16 | (phase gate) | the FULL `:core-java:integrationTest` task is genuinely green after legacy `@WithMockUser`/non-UUID-`.jwt()` classes were migrated to the production UUID-subject JWT auth shape (test-only; no production change) | `./gradlew :core-java:integrationTest` | `core-java/src/test/…` (7 migrated classes) | ✅ green (331 tests, 0 failed, 1 skipped) |

*The `OpenApiSnapshotTest` check-mode gate (regenerated in `adc1c58`) and `scripts/docs-freshness.sh` check mode (exit 0 at total 1573 / schema 57) are both green as of 23-15 Task 1/Task 2.*

---

## Wave 0 Requirements

- [x] `ShopStaffRlsPolicyIntegrationTest.java` — VSA-01 RLS + `user_directory` PII (copy `WebhookSubscriptionRlsPolicyIntegrationTest`)
- [x] `ShopAccessJitProvisionTest.java` — VSA-01 JIT idempotency (replaces the non-existent "migrate backfill" test — see RESEARCH §1-FLAG)
- [x] `ShopAccessEnforcementIntegrationTest.java` — VSA-02 cross-shop 403 / STAFF / read-scope / 403≠404
- [x] `StaffManagementIntegrationTest.java` — VSA-04 grant/revoke/last-GA-409
- [x] Jest: switcher + staff screen specs — VSA-03/04
- [x] Jest (plan 23-07): `hooks/use-shop-context.test.tsx`, `products-orders-shop-scope.test.tsx`, `marketing-kitchen-shop-scope.test.tsx` — VSA-03 screens-react-to-selected-shop
- [x] Playwright: 375px case added to `dashboard-mobile.spec.ts` — MOBL-01
- [x] `docs/metrics.json` + CLAUDE.md prose counts reconciled via `scripts/docs-freshness.sh --write`

*Framework install: none — all frameworks present.*

---

## Cross-Cutting Quality Contracts — Disposition

Per CLAUDE.md "Cross-Cutting Quality Contracts (design-time)", each dimension is dispositioned for this phase:

| Dimension | Disposition | Basis |
|-----------|-------------|-------|
| **Security** | **Central (covered)** | Phase introduces a new authZ boundary; each PLAN carries a `<threat_model>` grounded in RESEARCH §Security Domain STRIDE register; every `high` threat maps to a mitigation task/criterion. |
| **AI agent-readiness** | **Covered** | New mutating staff API (`/api/v1/staff/grant|revoke`) returns typed RFC 7807 errors + is provably idempotent (23-04 `insertGrantIfAbsent` ON CONFLICT). |
| **Web performance (mobile-first)** | **N/A (recorded, not dropped)** | The only new user-facing surfaces are a small client `ShopSwitcher` component mounted in the existing dashboard header/topbar (23-05) and client-side list-filtering added to already-rendered dashboard pages (23-07). No new route, no new above-the-fold image/font/bundle, no server-render change — negligible measurable LCP/CLS/INP impact on already-authenticated dashboard pages. MOBL-01's 375px Playwright spec already guards layout/overflow. |
| **SEO / discoverability** | **N/A (exempt)** | All Phase 23 surfaces are inside the authenticated dashboard (not a public/unauthenticated surface); storefront public read path is explicitly out of scope (CONTEXT spec_lock). |
| **Accessibility** | **Standard (contracted)** | Covered by existing UI standards + QA Phase 4; switcher/staff screen mirror existing accessible dashboard components. |

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live 375px visual confirmation of the D-06 switcher integrated into the mobile top bar / "More" sheet | MOBL-01 / VSA-03 | Visual placement/occlusion at real mobile width is best confirmed in a browser; the Playwright spec asserts no-overflow but the switcher's visual home is a judgment call | Drive the running dashboard at 375×812 (compose stack, port 3000), log in, confirm switcher reachable + no content occlusion (RESEARCH §7 has the baseline evidence) |
| Vendor-authenticated Playwright E2E (`dashboard-mobile.spec` 375px live run + `/dashboard/staff` click-through) | VSA-03 / VSA-04 / MOBL-01 | Requires a real Keycloak login — `E2E_VENDOR_PASSWORD` is not available in the execution session (documented limitation carried since 23-05 / 23-07 / 23-13) and port-3000 needs a frontend rebuild to serve the post-change image | Set `E2E_VENDOR_PASSWORD`, rebuild the frontend container, then `cd frontend && npx playwright test`. The Jest 375px + `dashboard-mobile.spec` static case are green; only the live authenticated run is deferred. |

*All other phase behaviors have automated verification. The Java + Jest suites (integrationTest 331/0, jest 360/360) are the load-bearing anti-false-green proof; the deferred item above is a browser-visual confirmation, not a logic gate.*

---

## Validation Sign-Off

- [x] All tasks have automated verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < ~60s (quick run)
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** validated 2026-07-21 (23-15 phase-gate reconcile — all rows carry a green automated command over the 23-16-green suite; live vendor-authenticated Playwright is the sole env-deferred manual check).
