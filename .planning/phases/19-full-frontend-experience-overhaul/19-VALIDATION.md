---
phase: 19
slug: full-frontend-experience-overhaul
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-11
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

> Filled by the planner: every task's `<acceptance_criteria>` must map to one row.
> Mandatory rows (from RESEARCH Validation Architecture — plans MUST carry these):

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| TBD | TBD | TBD | UIX-01 | — | `/` is public; no auth bypass introduced | Jest link-graph static test (zero orphan routes) + Playwright landing spec (desktop+mobile) | `npx jest link-graph` / `npx playwright test landing` | pending | pending |
| TBD | TBD | TBD | UIX-02 | — | dashboard mobile nav exposes no unauthenticated route | Playwright `mobile` project across 11 dashboard routes | `npx playwright test --project=mobile dashboard-nav` | pending | pending |
| TBD | TBD | TBD | UIX-03 | — | product name snapshot; no cross-tenant product read | JUnit unit + Testcontainers guest-checkout assertion (`productName != "Unknown Product"`) + backfill row-count proof | `./gradlew :core-java:test --tests '*OrderItem*' --tests '*PublicStorefront*'` | pending | pending |
| TBD | TBD | TBD | UIX-04 | address = PII → RLS + no PII in logs | V45 columns mirrored to `orders_aud`; audited-write test (Envers drift = latent 500, V38 precedent) | Testcontainers audited-write test + `./gradlew :core-java:integrationTest` | pending | pending |
| TBD | TBD | TBD | UIX-05 | — | per-shop scoping cannot leak cross-tenant products (RLS unchanged) | Testcontainers ProductRepository scoping test + storefront Playwright per-shop menu assertion | `./gradlew :core-java:integrationTest --tests '*Product*'` | pending | pending |
| TBD | TBD | TBD | UIX-06 | — | zero regression: full gate + tripwire list from RESEARCH § Regression Tripwires | full suite + `scripts/docs-freshness.sh` | all commands above | pending | pending |

---

## Regression Tripwires (from 19-RESEARCH.md — every one gets an explicit plan task or verify step)

- Playwright: checkout spec, csp-no-violations (force-dynamic/CSP nonce must survive landing page), storefront specs, header-snapshot.
- Jest: header-snapshot, session-callback, marketing component tests (operator-pitch, business-model-guide — shells added around them).
- Java: OrderService tests (OrderItem creation), ProductRepository/FTS tests incl. #96 NULL `search_vector` tripwires (shop_id assignment interacts), `RlsContractTest`, GdprErasure (orders_aud UPDATE policies must still cover new columns' rows).
- CI: docs-freshness (any test-count change → `scripts/docs-freshness.sh --write` in same commit), gitleaks (no creds in planning docs).
