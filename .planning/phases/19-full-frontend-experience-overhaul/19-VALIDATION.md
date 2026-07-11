---
phase: 19
slug: full-frontend-experience-overhaul
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-11
planned: 2026-07-11
plans: 9
waves: 4
---

# Phase 19 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source of truth: `19-RESEARCH.md` § Validation Architecture (per-deliverable methods + commands)
> and § Regression Tripwires (existing tests that assert on surfaces this phase changes).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (core-java) · Jest 29.7 (frontend) · Playwright 1.59 (e2e, incl. `mobile` 390×844 project) · go test (edge, untouched) |
| **Config file** | `core-java/build.gradle.kts` · `frontend/jest.config.js` · `frontend/playwright.config.ts` |
| **Quick run command** | scoped: `./gradlew :core-java:test --tests '<TouchedClass>*'` / `npx jest <touched paths>` |
| **Full suite command** | `./gradlew :core-java:test :core-java:integrationTest` + `cd frontend && npm test && npm run build` + Playwright suite against rebuilt Docker stack |
| **Estimated runtime** | quick ~1–3 min · full gate ~25 min (integrationTest ~20 min in CI) |

---

## Sampling Rate

- **After every task commit:** Run the scoped quick command for the touched module (jest for frontend tasks, scoped gradle tests for Java tasks). Frontend TS changes additionally require `npm run build` — jest does NOT typecheck.
- **After every plan wave:** Full suite command; Playwright only after Docker rebuild of changed containers.
- **Before `/gsd:verify-work`:** Full suite green + docs-freshness (`scripts/docs-freshness.sh`) exit 0 + browser UAT with screenshots (images verified `naturalWidth > 0`; SSE pages use `domcontentloaded`, never `networkidle`).
- **Max feedback latency:** 300 seconds (scoped commands); full gate reserved for wave boundaries.

---

## Per-Task Verification Map

> Every task's `<acceptance_criteria>` maps to a row below. Plans 19-01..19-09 (9 plans, 4 waves).
> Grep gates are exact-string assertions; Testcontainers proves real-Postgres/RLS/Envers behaviour.

| Row | Plan · Task | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | Status |
|-----|-------------|------|-------------|------------|-----------------|-----------|-------------------|--------|
| V-01 | 19-01 · T1/T2/T3 | 1 | UIX-03 | T-19-01-04 | product-name snapshot at write; no cross-tenant read (RLS) | JUnit + Testcontainers (`productName != "Unknown Product"` on fresh guest order + backfill) | `./gradlew :core-java:test --tests '*PublicStorefrontServiceTest'` | planned |
| V-02 | 19-01 · T1/T3 | 1 | UIX-04 (backend) | T-19-01-03/04 | address = PII → orders_aud mirror + GDPR scrub + no address in logs | Testcontainers audited-write (Envers no-drift, V38 precedent) + GDPR erasure address assertion | `./gradlew :core-java:integrationTest --tests '*OrderFulfilmentAudit*' --tests '*GdprErasure*'` | planned |
| V-03 | 19-02 · T1/T2 | 1 | UIX-05 | T-19-02-01/02 | per-shop scoping stays tenant-scoped (RLS); dev seeder never runs in test/prod | Testcontainers ProductRepository scoping (shop A ≠ shop B, no NULL bleed) + `@Profile("dev")` gate | `./gradlew :core-java:integrationTest --tests '*ProductRepositoryScoping*' --tests '*Product*'` | planned |
| V-04 | 19-03 · T1/T2/T3 | 1 | UIX-01 | T-19-03-01/04 | `/` public, no auth-gated data in public shell; no internal routes in public nav | Jest link-graph static (zero orphans) + landing render test + no-npm-churn assertion | `npx jest app/__tests__/landing.test.tsx __tests__/link-graph.test.ts` | planned |
| V-05 | 19-04 · T1/T2/T3 | 2 | UIX-02 | T-19-04-01 | mobile nav exposes only the same authenticated dashboard routes | Jest dashboard-shell + Playwright `--project=mobile` across 11 routes (sidebar hidden, bottom bar shown) | `npx playwright test --project=mobile dashboard-mobile.spec` | planned |
| V-06 | 19-05 · T1/T2 | 2 | UIX-01 | T-19-05-01 | `/track` reuses IDOR-hardened endpoint; no forced session; marketing hex → tokens | Jest marketing token assertions + track guest-lookup test; hex gate | `npx jest components/marketing/__tests__ track` ; `grep -rlE "#[0-9a-fA-F]{3,8}" components/marketing/*.tsx` == 0 | planned |
| V-07 | 19-06 · T1/T2/T3 | 2 | UIX-04 (frontend) | T-19-06-01 | client fee is preview-only; server total authoritative; address not client-logged | Jest checkout (fulfilment/postcode/fee-parity) + Playwright storefront-flows (fee before pay, per-shop menu) | `npx jest app/shop/[slug]/checkout/__tests__/checkout.test.tsx` | planned |
| V-08 | 19-07 · T1/T2/T3 | 2 | UIX-06 (F) | T-19-07-01 | delivery address (PII) read-only render, not logged | Jest kitchen + OrderDetailPanel (no "Unknown Product") + Playwright kitchen-flow (domcontentloaded) | `npx jest app/dashboard/kitchen/__tests__/page.test.tsx components/dashboard/orders/__tests__/OrderDetailPanel.test.tsx` | planned |
| V-09 | 19-08 · T1/T2/T3 | 3 | UIX-06 (I) | T-19-08-01/03 | 401→200 probe weakens no gate (server-side gate authoritative) | Jest palette-discipline gate + customer-auth session tests | `npx jest __tests__/palette-discipline.test.ts app/api/customer-auth/__tests__/route.test.ts` | planned |
| V-10 | 19-09 · T1/T2/T3 | 4 | UIX-01..06 | T-19-09-01/03 | CSP nonce survives; no PII in logs; count reconcile hides no dropped test | Full suite + all UIX grep gates + Playwright mobile+desktop + docs-freshness + browser UAT | `./gradlew test integrationTest` + `npm test && npm run build` + `scripts/docs-freshness.sh` | planned |

**Mandatory-row coverage (RESEARCH § Validation Architecture):** UIX-01→V-04/V-06/V-10 · UIX-02→V-05 · UIX-03→V-01 · UIX-04→V-02/V-07 · UIX-05→V-03 · UIX-06→V-08/V-09/V-10. All six requirements carry ≥1 automated row; every row has an `<automated>` command (Nyquist compliant).

---

## Regression Tripwires (from 19-RESEARCH.md — each gets an explicit plan task or verify step)

- **Playwright:** checkout spec (19-06 T3), csp-no-violations + header-snapshot (19-09 T2 — force-dynamic/CSP nonce must survive the landing page), storefront-flows (19-06 T3), kitchen-flow (19-07 T3).
- **Jest:** header-snapshot + session-callback (19-09 T2 / 19-08 T3), marketing component tests operator-pitch + business-model-guide (19-05 T1 — shells added + hex→token), dashboard-shell (19-04 T2 — mobile bars).
- **Java:** OrderService/PublicStorefrontService tests — OrderItem creation + fulfilment (19-01 T3); ProductRepository/FTS tests incl. #96 NULL `search_vector` tripwires — shop_id assignment interacts (19-02 T2); `RlsContractTest` — RLS table-level unchanged, confirm green (19-01/19-02); GdprErasure — orders_aud UPDATE policies must still cover the new address columns' rows (19-01 T2/T3).
- **CI:** docs-freshness — any test-count change reconciled once at closure via `scripts/docs-freshness.sh --write` in 19-09 T1 (generator is the arbiter; earlier plans do NOT edit metrics.json); gitleaks/GitGuardian — no creds in planning docs (19-09 threat T-19-09-SC).

---

## Wave 0 gaps (net-new test scaffolds created during execution)

- `frontend/__tests__/link-graph.test.ts` (19-03 T3) — orphan-route guard (UIX-01).
- `frontend/app/__tests__/landing.test.tsx` (19-03 T2) — landing renders + door links (UIX-01).
- `frontend/e2e/dashboard-mobile.spec.ts` (19-04 T3) — 11 routes at 390px (UIX-02).
- `core-java/.../order/OrderFulfilmentAuditIntegrationTest.java` (19-01 T3) — audited order write post-V45 proves no Envers drift (UIX-04, Pitfall 1).
- `core-java/.../product/ProductRepositoryScopingIntegrationTest.java` (19-02 T2) — per-shop scoping (UIX-05).
- `frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx` (19-06 T3) — fulfilment toggle + postcode + fee preview (UIX-04).
- `frontend/__tests__/palette-discipline.test.ts` (19-08 T3) — purple/text-[10px]/marketing-hex/href-track grep gates (UIX-06).
- `components/ui/sheet.tsx` vendored (19-03 T1) before mobile-tab-bar work.
- Regenerate `docs/metrics.json` (schema 43→45; new jest/playwright/java counts) — 19-09 T1.
