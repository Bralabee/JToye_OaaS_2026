---
phase: 12
slug: spring-security-response-headers-frontend-csp
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-18
---

# Phase 12 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (Java)** | JUnit 5 + Spring Boot Test + MockMvc + Testcontainers 1.21.3 |
| **Framework (Frontend)** | Jest 29.7.0 + @testing-library/react + Playwright 1.59.1 |
| **Config files** | `core-java/build.gradle.kts`, `frontend/jest.config.ts`, `frontend/playwright.config.ts` |
| **Quick run (Java)** | `./gradlew :core-java:test --tests "*SecurityHeaders*"` |
| **Quick run (Frontend)** | `cd frontend && npm test -- __tests__/csp.test.ts` |
| **Full suite (Java)** | `./gradlew :core-java:test` |
| **Full suite (Frontend)** | `cd frontend && npm test && npx playwright test` |
| **Estimated runtime** | ~15s quick Java · ~8s quick FE · ~90s full suite |

---

## Sampling Rate

- **After every task commit:** Run quick-run command for the modified layer (Java or FE)
- **After every plan wave:** Run full suite for the modified layer
- **Before `/gsd-verify-work`:** Full Java + Frontend + Playwright suites green
- **Max feedback latency:** 15 seconds for quick unit/integration; 90 seconds for full suite + Playwright

---

## Per-Task Verification Map

> Populated by planner. Every task with acceptance criteria gets a row. Status ⬜ until execution.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 12-01-01 | 01 | 1 | SEC-03 | T-12-01 (Clickjacking) | Spring responses include `X-Frame-Options: DENY` | integration (MockMvc) | `./gradlew :core-java:test --tests "*SecurityHeadersIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 12-01-02 | 01 | 1 | SEC-03 | T-12-02 (MIME sniffing) | `X-Content-Type-Options: nosniff` on all 200 + 4xx | integration | `./gradlew :core-java:test --tests "*SecurityHeadersIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 12-01-03 | 01 | 1 | SEC-03 | T-12-03 (Referrer leak) | `Referrer-Policy: strict-origin-when-cross-origin` on every response | integration | `./gradlew :core-java:test --tests "*SecurityHeadersIntegrationTest*"` | ❌ W0 | ⬜ pending |
| 12-01-04 | 01 | 2 | SEC-03 | T-12-04 (Protocol downgrade) | HSTS `max-age=31536000; includeSubDomains` present in `prod`, absent in `dev` | integration (profile-activated) | `./gradlew :core-java:test --tests "*SecurityHeadersProdProfileTest*"` + `*SecurityHeadersDevProfileTest*` | ❌ W0 | ⬜ pending |
| 12-02-01 | 02 | 1 | SEC-02 | T-12-05 (XSS) | `next.config.mjs` emits `Content-Security-Policy-Report-Only` with `default-src 'self'`, `frame-ancestors 'none'`, Stripe + NextAuth allowlist | unit (fetch-based) | `npm test -- __tests__/csp-headers.test.ts` | ❌ W0 | ⬜ pending |
| 12-02-02 | 02 | 1 | SEC-02 | T-12-06 (3DS failure) | `frame-src` includes `hooks.stripe.com` + `js.stripe.com` | unit (header parse) | `npm test -- __tests__/csp-headers.test.ts` | ❌ W0 | ⬜ pending |
| 12-02-03 | 02 | 2 | SEC-02 | T-12-07 (NextAuth signin) | `form-action` includes Keycloak origin; `connect-src` includes API + WS origins | unit | `npm test -- __tests__/csp-headers.test.ts` | ❌ W0 | ⬜ pending |
| 12-02-04 | 02 | 2 | SEC-02 | T-12-08 (CSP violation) | Playwright run across `/`, `/shop/[slug]`, `/dashboard` produces ZERO `securitypolicyviolation` events | e2e | `npx playwright test tests/csp-no-violations.spec.ts` | ❌ W0 | ⬜ pending |
| 12-02-05 | 02 | 3 | SEC-02, SEC-03 | — | Header snapshot file committed; CI fails on regression | CI check | `./gradlew :core-java:test --tests "*HeaderSnapshot*"` + `npm test -- __tests__/header-snapshot.test.ts` | ❌ W0 | ⬜ pending |
| 12-02-06 | 02 | 3 | SEC-02 | — | Cut `Content-Security-Policy-Report-Only` → `Content-Security-Policy` after 1-week observation (or immediate enforce if planner elects) | manual gate | — | N/A | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

**File-exists column** reflects pre-execution state. ❌ W0 = Wave 0 creates it. Planner refines this table.

---

## Wave 0 Requirements

- [ ] `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java` — MockMvc header assertions for SEC-03 criteria 1, 3
- [ ] `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersProdProfileTest.java` — `@ActiveProfiles("prod")` HSTS presence
- [ ] `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersDevProfileTest.java` — `@ActiveProfiles("dev")` HSTS absence
- [ ] `frontend/__tests__/csp-headers.test.ts` — Jest fetch-based header parse/assertion
- [ ] `frontend/__tests__/header-snapshot.test.ts` — golden-file header snapshot test
- [ ] `frontend/tests/csp-no-violations.spec.ts` — Playwright CSP violation listener test
- [ ] `.github/workflows/ci-cd.yaml` — add Playwright job if not present (or justify jest-fetch alternative per RESEARCH.md §6)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Report-Only → enforce cutover | SEC-02 | One-week observation period for CSP report collection; human judgment required to flip the header name | (1) Deploy Report-Only to staging. (2) Drive Stripe 3DS + NextAuth flows. (3) Verify zero `report-uri` entries over 7 days. (4) Rename response header to `Content-Security-Policy` in `next.config.mjs` and redeploy. |
| Production Stripe 3DS end-to-end | SEC-02 | Live-card test requires Stripe test mode + real browser with 3DS challenge. Not reliably automatable. | Use Stripe test card `4000 0027 6000 3184` (requires authentication) against staging. Confirm 3DS iframe renders, no CSP violation in console, charge completes. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references (7 files listed above)
- [ ] No watch-mode flags (`--watch`, `--watchAll`)
- [ ] Feedback latency < 90s full suite including Playwright
- [ ] `nyquist_compliant: true` set in frontmatter (planner/checker will flip after plan finalization)

**Approval:** pending
