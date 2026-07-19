---
phase: 23
slug: vendor-scoped-access-responsive-dashboard-nav
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-19
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
| **Count gate** | `scripts/docs-freshness.sh --write` (baseline 1456; enforced by `.github/workflows/docs-freshness.yml`) |
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
| VSA-01 | `shop_staff`/`_aud`/`user_directory` RLS+FORCE proven cross-tenant under NOSUPERUSER | integration (Testcontainers) | `./gradlew test --tests "*ShopStaffRlsPolicyIntegrationTest"` | ❌ W0: `ShopStaffRlsPolicyIntegrationTest.java` (copy `WebhookSubscriptionRlsPolicyIntegrationTest`) | ⬜ pending |
| VSA-01 | `user_directory` email PII hidden cross-tenant (FORCE load-bearing) | integration | same suite | ❌ W0 | ⬜ pending |
| VSA-01 | JIT-provision idempotent (concurrent first-requests → 1 GROUP_ADMIN row) | integration | `./gradlew test --tests "*ShopAccessJitProvisionTest"` | ❌ W0: `ShopAccessJitProvisionTest.java` | ⬜ pending |
| VSA-01 | Contract sweeps green (RLS+FORCE, no raw `::uuid` cast) | integration | `./gradlew test --tests "*RlsContractTest"` | ✅ exists (auto-covers new tables) | ⬜ pending |
| VSA-02 | SHOP_MANAGER on shop A → typed 403 (distinct `type`) on shop B write | integration | `./gradlew test --tests "*ShopAccessEnforcementIntegrationTest"` | ❌ W0 | ⬜ pending |
| VSA-02 | STAFF read-only: can transition order state, denied catalogue write | integration | same suite | ❌ W0 | ⬜ pending |
| VSA-02 | Read-scoping: scoped user's list returns only granted shops | integration | same suite | ❌ W0 | ⬜ pending |
| VSA-02 | 403 body `type` ≠ RLS 404 `type` (provably distinct) | integration | same suite | ❌ W0 | ⬜ pending |
| VSA-03 | Switcher persists selection (localStorage); non-GA cannot see "apply to all" | unit (Jest) | `cd frontend && npx jest components/dashboard` | ❌ W0 | ⬜ pending |
| VSA-04 | list/grant/revoke; grant→access, revoke→403; last-GROUP_ADMIN→409 | unit (Jest) + integration | `npx jest` + `./gradlew test --tests "*StaffManagementIntegrationTest"` | ❌ W0 | ⬜ pending |
| MOBL-01 | 375px: sidebar hidden, tab bar visible, no occlusion/overflow | e2e (Playwright) + unit (Jest) | `npx playwright test --project=mobile dashboard-mobile.spec` | ⚠️ 390px exists — add 375px case | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `ShopStaffRlsPolicyIntegrationTest.java` — VSA-01 RLS + `user_directory` PII (copy `WebhookSubscriptionRlsPolicyIntegrationTest`)
- [ ] `ShopAccessJitProvisionTest.java` — VSA-01 JIT idempotency (replaces the non-existent "migrate backfill" test — see RESEARCH §1-FLAG)
- [ ] `ShopAccessEnforcementIntegrationTest.java` — VSA-02 cross-shop 403 / STAFF / read-scope / 403≠404
- [ ] `StaffManagementIntegrationTest.java` — VSA-04 grant/revoke/last-GA-409
- [ ] Jest: switcher + staff screen specs — VSA-03/04
- [ ] Playwright: 375px case added to `dashboard-mobile.spec.ts` — MOBL-01
- [ ] `docs/metrics.json` + CLAUDE.md prose counts reconciled via `scripts/docs-freshness.sh --write`

*Framework install: none — all frameworks present.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live 375px visual confirmation of the D-06 switcher integrated into the mobile top bar / "More" sheet | MOBL-01 / VSA-03 | Visual placement/occlusion at real mobile width is best confirmed in a browser; the Playwright spec asserts no-overflow but the switcher's visual home is a judgment call | Drive the running dashboard at 375×812 (compose stack, port 3000), log in, confirm switcher reachable + no content occlusion (RESEARCH §7 has the baseline evidence) |

*All other phase behaviors have automated verification.*

---

## Validation Sign-Off

- [ ] All tasks have automated verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < ~60s (quick run)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
