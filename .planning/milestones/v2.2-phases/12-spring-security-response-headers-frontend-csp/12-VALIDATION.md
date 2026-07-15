---
phase: 12
slug: spring-security-response-headers-frontend-csp
status: planner-refined
nyquist_compliant: true
wave_0_complete: false
created: 2026-04-18
refined: 2026-04-18
---

# Phase 12 — Validation Strategy

> Per-phase validation contract. Refined by the planner 2026-04-18 to match the final PLAN task IDs (12-01-01..04, 12-02-01..05, 12-02-06 [CI wiring], 12-02-07 [manual gate]). Every executable task has an `<automated>` verify; the single manual gate (12-02-07 Report-Only → enforce cutover) is explicitly catalogued below.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (Java)** | JUnit 5 + Spring Boot Test + MockMvc + Testcontainers 1.21.3 |
| **Framework (Frontend)** | Jest 29.7.0 + @testing-library/react + Playwright 1.59.1 |
| **Config files** | `core-java/build.gradle.kts`, `frontend/jest.config.ts`, `frontend/playwright.config.ts` |
| **Quick run (Java)** | `./gradlew :core-java:test --tests "*SecurityHeaders*"` |
| **Quick run (Frontend)** | `cd frontend && npm test -- __tests__/csp-headers.test.ts __tests__/header-snapshot.test.ts` |
| **Full suite (Java)** | `./gradlew :core-java:test` |
| **Full suite (Frontend)** | `cd frontend && npm test` |
| **Playwright (local/staging only)** | `cd frontend && PLAYWRIGHT_BASE_URL=<url> npx playwright test e2e/csp-no-violations.spec.ts` |
| **CI gate (Frontend)** | `.github/workflows/ci-cd.yaml` step `Run frontend Jest tests` — executes `npm test -- --ci --watchAll=false` after `npm run build` (wired in Task 12-02-06) |
| **Estimated runtime** | ~15s quick Java · ~6s quick FE · ~90s full suite (Playwright not in CI) |

---

## Sampling Rate

- **After every task commit:** Run quick-run command for the modified layer (Java or FE)
- **After every plan wave:** Run full suite for the modified layer
- **Before `/gsd-verify-work`:** Full Java + Jest suites green; Playwright spec enumerates without error
- **Max feedback latency:** 15 seconds for quick unit/integration; 90 seconds for full suite

---

## Per-Task Verification Map

> Rows match final PLAN task IDs. Status ⬜ until execution.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 12-01-01 | 01 | 1 | SEC-03 | T-12-01, T-12-02, T-12-03 | SecurityHeadersIntegrationTest fails (RED) asserting X-Frame-Options DENY + X-Content-Type-Options nosniff + Referrer-Policy strict-origin-when-cross-origin on 200 and 401 | integration (MockMvc, TDD RED) | `./gradlew :core-java:test --tests "uk.jtoye.core.security.SecurityHeadersIntegrationTest"` (expected: FAIL) | creates test file (W0) | ⬜ pending |
| 12-01-02 | 01 | 1 | SEC-03 | T-12-04 | SecurityHeadersProdProfileTest fails (RED) asserting HSTS max-age+includeSubDomains under @ActiveProfiles("prod") with .secure(true); SecurityHeadersDevProfileTest asserts HSTS absence under @ActiveProfiles("dev") | integration (profile-gated, TDD RED for prod) | `./gradlew :core-java:test --tests "*SecurityHeadersProdProfileTest*" --tests "*SecurityHeadersDevProfileTest*"` (expected: prod FAIL, dev pass trivially) | creates 2 test files (W0) | ⬜ pending |
| 12-01-03 | 01 | 1 | SEC-03 | T-12-01..04 | SecurityConfig.java `.headers(...)` block added with frameOptions(DENY), contentTypeOptions, referrerPolicy, profile-gated HSTS; all 3 Wave-0 test classes GREEN | integration (TDD GREEN) | `./gradlew :core-java:test --tests "*SecurityHeaders*"` (expected: BUILD SUCCESSFUL) | modifies SecurityConfig.java | ⬜ pending |
| 12-01-04 | 01 | 1 | SEC-03 | T-12-01..04 | Java-side snapshot file committed + headerSnapshotMatchesGolden test asserts three curated headers match golden exactly | integration (snapshot) | `./gradlew :core-java:test --tests "uk.jtoye.core.security.SecurityHeadersIntegrationTest.headerSnapshotMatchesGolden"` | creates snapshot file | ⬜ pending |
| 12-02-01 | 02 | 1 | SEC-02 | T-12-05, T-12-06, T-12-07 | csp-headers.test.ts fails (RED) asserting Content-Security-Policy-Report-Only with default-src 'self', frame-ancestors 'none', per-directive Stripe allowlist (script-src + frame-src + connect-src asserted individually via parseCsp helper), Keycloak form-action, API+WS connect-src, explicit nosniff/Referrer-Policy/Permissions-Policy header assertions in test body | unit (Jest, TDD RED) | `cd frontend && npm test -- __tests__/csp-headers.test.ts` (expected: FAIL) | creates test file (W0) | ⬜ pending |
| 12-02-02 | 02 | 1 | SEC-02 | T-12-05, T-12-06, T-12-07, T-12-08 | next.config.mjs emits Report-Only CSP + baseline headers; all 7 csp-headers tests GREEN; middleware.ts unchanged; fixed-string grep (`grep -cF`) acceptance replaces broken escaped-regex form | unit (TDD GREEN) | `cd frontend && npm test -- __tests__/csp-headers.test.ts` (expected: all pass) | modifies next.config.mjs | ⬜ pending |
| 12-02-03 | 02 | 1 | SEC-02, SEC-03 | — | Jest snapshot regression test with committed .snap file — any CSP directive / baseline header drift fails until `npm test -- -u` regenerates | unit (snapshot) | `cd frontend && npm test -- __tests__/header-snapshot.test.ts` | creates test + .snap | ⬜ pending |
| 12-02-04 | 02 | 1 | SEC-02 | — | playwright.config.ts baseURL parameterized via PLAYWRIGHT_BASE_URL env var; --list still enumerates existing specs without error | config change (lint) | `cd frontend && npx playwright test --list` | modifies config | ⬜ pending |
| 12-02-05 | 02 | 1 | SEC-02 | T-12-06, T-12-07, T-12-08 | e2e/csp-no-violations.spec.ts file exists with 3 tests, uses page.on('console') CSP violation listener, documented local/staging only (NOT in CI) | e2e structure (lint) | `cd frontend && npx playwright test e2e/csp-no-violations.spec.ts --list` | creates spec | ⬜ pending |
| 12-02-06 | 02 | 1 | SEC-02 | T-12-08 | `.github/workflows/ci-cd.yaml` frontend-build job gains a `Run frontend Jest tests` step AFTER `npm run build`, executing `npm test -- --ci --watchAll=false`. Closes SEC-02 criterion 5 (CI fails on any CSP header regression). Ordering check: line(`npm test`) > line(`npm run build`). | CI config (lint + ordering) | `grep -n "npm test\|npm run build" .github/workflows/ci-cd.yaml` + ordering assertion | modifies ci-cd.yaml | ⬜ pending |
| 12-02-07 | 02 | 2 | SEC-02 | T-12-08 | Report-Only → enforce cutover: manual 7-day observation in staging with Stripe 3DS + NextAuth signin + Playwright run, then header-key flip from `Content-Security-Policy-Report-Only` to `Content-Security-Policy` + snapshot regen. CI gate from Task 12-02-06 will fail on the flip PR unless the regenerated snapshot is committed alongside. | manual gate (checkpoint:human-verify) | N/A — human executes via `curl -sI <staging>/` + manual Stripe/NextAuth smoke + `cd frontend && npm test -- -u __tests__/header-snapshot.test.ts` after flip | — | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

**Nyquist continuity check:**
- Plan 01 has 4 tasks, all with `<automated>` verify — no 3-consecutive-missing chain.
- Plan 02 has 7 tasks. Tasks 01-06 all have automated verify; Task 07 is the sole manual gate. No consecutive missing-verify sequence.
- Both plans' Wave 0 tasks (create RED test) immediately feed the subsequent GREEN task — TDD sampling meets Nyquist.

---

## Wave 0 Requirements (all refined to match final task IDs)

- [ ] `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java` — MockMvc header assertions for X-Frame-Options, X-Content-Type-Options, Referrer-Policy on 200 + 401 + snapshot test (Tasks 12-01-01 + 12-01-04)
- [ ] `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersProdProfileTest.java` — `@ActiveProfiles("prod")` HSTS presence assertion using `.secure(true)` (Task 12-01-02)
- [ ] `core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersDevProfileTest.java` — `@ActiveProfiles("dev")` HSTS absence assertion including `.secure(true)` negative case (Task 12-01-02)
- [ ] `core-java/src/test/resources/security-headers-snapshot.txt` — Java-side golden snapshot for regression (Task 12-01-04)
- [ ] `frontend/__tests__/csp-headers.test.ts` — Jest unit test importing next.config.mjs via dynamic import with jest.resetModules isolation, including `parseCsp` helper + per-directive Stripe assertions (Task 12-02-01)
- [ ] `frontend/__tests__/header-snapshot.test.ts` + `frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap` — Jest snapshot regression (Task 12-02-03)
- [ ] `frontend/e2e/csp-no-violations.spec.ts` — Playwright CSP violation listener test, LOCAL/STAGING only (Task 12-02-05)
- [ ] `.github/workflows/ci-cd.yaml` — ADD Jest test step per Task 12-02-06 (Jest is the CI gate — RESEARCH.md §6 Q4 default). Step inserted AFTER `npm run build` and BEFORE `Upload test results`. Playwright stays OUT of CI (local/staging only).

---

## Manual-Only Verifications

| Behavior | Requirement | Task | Why Manual | Test Instructions |
|----------|-------------|------|------------|-------------------|
| Report-Only → enforce cutover | SEC-02 | 12-02-07 | 1-week observation period + human judgment on browser console output; live-card 3DS flow requires human interaction with Stripe test-mode challenge | See Plan 02 Task 12-02-07 `<how-to-verify>` (7 numbered steps) |
| Stripe 3DS live | SEC-02 | 12-02-07 step 3 | Test card `4000 0027 6000 3184` triggers 3DS challenge — must click through in real browser | Part of cutover gate; verified pre-flip and post-flip |
| NextAuth Keycloak signin | SEC-02 | 12-02-07 step 4 | OIDC redirect flow requires browser form submission against live Keycloak | Part of cutover gate; verified pre-flip and post-flip |

---

## Validation Sign-Off

- [x] All executable tasks have `<automated>` verify; single manual gate catalogued in Manual-Only table
- [x] Sampling continuity: no 3 consecutive tasks without automated verify (verified row-by-row above)
- [x] Wave 0 covers all 8 MISSING references (listed above — ci-cd.yaml now included as a required modification)
- [x] No watch-mode flags in CI invocation (`npm test -- --ci --watchAll=false` explicitly disables watch)
- [x] Feedback latency < 90s full suite (Playwright excluded from CI per RESEARCH.md Q4)
- [x] `nyquist_compliant: true` set in frontmatter
- [x] CI gate wired for SEC-02 criterion 5 via Task 12-02-06 (new this revision)

**Approval:** planner-refined, ready for execution
