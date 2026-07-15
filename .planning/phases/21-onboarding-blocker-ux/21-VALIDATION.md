---
phase: 21
slug: onboarding-blocker-ux
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-14
---

# Phase 21 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from `21-RESEARCH.md` §Validation Architecture. Every ONBD-01..05 behavior maps to a named test layer + analog.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers 1.21.3 (real Postgres 15 + RLS) + Spring MockMvc (backend); Jest 29.7.0 + @testing-library/react (frontend unit); Playwright 1.59.1 (E2E) |
| **Config file** | `core-java/build.gradle.kts` (`test` + `integrationTest` tasks, `@Tag("testcontainers")`); `frontend/jest.config.*`; `frontend/playwright.config.ts` |
| **Quick run command** | `./gradlew test --tests "*Onboarding*"` (backend unit + SM) · `npm test -- onboarding` (frontend) |
| **Full suite command** | `./gradlew test integrationTest` · `npm test && npm run build` (Jest + tsc typecheck gate) · `npx playwright test` (requires rebuilt compose stack up) |
| **Estimated runtime** | backend unit ~30s; integrationTest several min (Testcontainers); frontend unit ~20s; Playwright journey ~1–2 min |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*Onboarding*"` and/or `npm test -- onboarding`
- **After every plan wave:** Run `./gradlew test integrationTest` + `npm test && npm run build`
- **Before `/gsd:verify-work`:** Full backend suite green + `npm run build` green + Playwright journey green + `scripts/docs-freshness.sh` OK
- **Max feedback latency:** ~30s (unit sampling); full suite reserved for wave merges

---

## Per-Task Verification Map

> Task IDs bind at planning time. Rows below map each requirement/behavior to its plan (per the ROADMAP est. skeleton 21-01..04), test type, and closest existing analog.

| Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command / Analog | File Exists | Status |
|------|------|-------------|------------|-----------------|-----------|----------------------------|-------------|--------|
| 21-01 | 1 | ONBD-01 | — | WITHDRAW valid from 5 pre-live states; terminal rejects WITHDRAW | JUnit SM (unit) | `VendorOnboardingStateMachineServiceTest::withdrawFromEachSource` + `illegalTransitionsThrow` | ✅ present (`:133-165`) | ⬜ pending |
| 21-01 | 1 | ONBD-01 | V4/V1 | `POST /onboarding/withdraw` → WITHDRAWN; illegal source → RFC 7807 400; SM stays sole writer of `Shop.published` | Controller/Testcontainers | new, analog `OnboardingAdminQueueIntegrationTest` | ❌ W0 | ⬜ pending |
| 21-01 | 1 | ONBD-02 | V5 | Update companyNumber (blank=sole trader) only in DRAFT/ACTION_REQUIRED; else RFC 7807 400; garbage → clean 400 (`@Size(32)`+`@Pattern` reuse, never reaches CH API) | Controller/Testcontainers | new, analog `OnboardingCompanyNumberValidationIntegrationTest` + `OnboardingResubmitIntegrationTest` | ❌ W0 | ⬜ pending |
| 21-02 | 1 | ONBD-03 | V1 | `reviewPending` derived true iff `status==VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING` (computed in `toDto`) | JUnit/Testcontainers | new test on `getMyOnboarding`/`toDto` | ❌ W0 | ⬜ pending |
| 21-02 | 1 | ONBD-03 | V4/V1 | gate-resolve PASS/WAIVE → recompute → GATES_PASSED → advance from VERIFYING (SM sole writer); FAIL → GATE_FAILED → ACTION_REQUIRED | Testcontainers + `GateChainRunnerTest` (unit) | analog admin-queue integration | ❌ W0 | ⬜ pending |
| 21-02 | 1 | ONBD-03 | V4 | gate-resolve writes `vendor_onboarding_gate_aud` (Envers); non-admin → 403; foreign tenant → 404 (RLS FORCE, no existence oracle) | Testcontainers (NOSUPERUSER) | analog `adminRejectsWithReason_…Envers` (`:242-245`) + `nonAdminGets403…` + RLS (`:287-319`) | ❌ W0 | ⬜ pending |
| 21-02 | 1 | ONBD-03 | V4 | Admin review queue lists VERIFYING+MANUAL_REVIEW applications | Testcontainers | analog `adminListsPending…` | ❌ W0 | ⬜ pending |
| 21-02 | 1 | ONBD-05 | V7 | `rejectionReason` present on vendor `OnboardingDto` | JUnit DTO serialization / Testcontainers | new | ❌ W0 | ⬜ pending |
| 21-02 | 1 | Outbox seam (D-01) | V4/Info-Disclosure | stall emits an `onboarding.events` outbox row (tenant-stamped); flusher deserializes it without poisoning the `PaymentEvent` cast | Testcontainers + flusher unit | new; assert `SELECT … FROM payment_event_outbox WHERE exchange='onboarding.events'` + analog flusher tests | ❌ W0 | ⬜ pending |
| 21-03 | 2 | ONBD-04 | V7 | Each `(gateType,status)` remediation block renders why + what-to-do + deep-link (allergen "fix N products", company-number inline edit, FHRS shop-edit) | Jest (per block) | new blocks in `onboarding/__tests__/page.test.tsx` | ❌ W0 (file exists) | ⬜ pending |
| 21-03 | 2 | ONBD-03 | — | In-review copy ("reviewer checks within N business days", config-injected) + polling back-off once `reviewPending` | Jest | `onboarding/__tests__/page.test.tsx` | ❌ W0 | ⬜ pending |
| 21-03 | 2 | ONBD-01 | — | Withdraw confirm dialog + terminal WITHDRAWN copy | Jest | new block in `onboarding/__tests__/page.test.tsx` | ❌ W0 | ⬜ pending |
| 21-03 | 2 | ONBD-02 | — | Inline company-number edit calls the update endpoint | Jest | `onboarding/__tests__/page.test.tsx` | ❌ W0 | ⬜ pending |
| 21-03 | 2 | ONBD-05 | V7 | REJECTED/SUSPENDED render `rejectionReason` + config support channel (no bare "contact support", no hardcoded literal) | Jest | new blocks | ❌ W0 | ⬜ pending |
| 21-04 | 3 | ONBD-05 (journey) | — | Blocked onboarding end-to-end: bad company number → fix inline → resubmit → live | Playwright | new spec `frontend/e2e/onboarding-blocked-flow.spec.ts` | ❌ W0 | ⬜ pending |
| 21-04 | 3 | metrics gate | — | `docs/metrics.json` reconciled; `docs-freshness` CI green (counts move from 1257) | CLI | `scripts/docs-freshness.sh --write` then commit | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Backend controller/Testcontainers tests for withdraw, update, gate-resolve, review-queue, outbox emission — analogs: `OnboardingAdminQueueIntegrationTest`, `OnboardingResubmitIntegrationTest`, `OnboardingCompanyNumberValidationIntegrationTest`, `GateChainRunnerTest`
- [ ] Jest blocks added to existing `frontend/app/dashboard/onboarding/__tests__/page.test.tsx` and `.../approvals/__tests__/page.test.tsx`
- [ ] New Playwright spec `frontend/e2e/onboarding-blocked-flow.spec.ts`
- [ ] `scripts/docs-freshness.sh --write` + commit updated `docs/metrics.json`
- [ ] Framework install: **none needed** — JUnit/Testcontainers/Jest/Playwright all present

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| End-to-end "in review" honesty on the live rebuilt stack (real MANUAL_REVIEW gate from a fuzzy FHRS mismatch shows the honest copy, not the forever-spinner) | ONBD-03 | Requires the rebuilt compose stack + a genuinely fuzzy FHRS shop; the Playwright journey covers the company-number path, this covers the FHRS-manual-review path | Rebuild ALL containers → create onboarding with an unmatchable shop name/address → confirm vendor page renders "In review" + admin review queue shows the entry + gate-resolve advances it |

*The company-number blocked journey is automated in Playwright (21-04); the FHRS-manual-review visual path is the one manual confirmation.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s (unit sampling)
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
