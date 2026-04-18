---
status: complete
phase: 12-spring-security-response-headers-frontend-csp
source: [12-01-SUMMARY.md, 12-02-SUMMARY.md]
started: 2026-04-18T14:05:00Z
updated: 2026-04-18T14:35:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke — Spring security headers
expected: Start Spring Boot app → `curl -sI http://localhost:8080/api/v1/shops` returns `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin` on a live (not MockMvc) request
result: pass
verified: Rebuilt `jtoye_oaas_2026-core-java` image from HEAD (commit 953a25b), force-recreated container, healthy in 20s. `curl -sI http://localhost:9090/api/v1/shops` → HTTP/1.1 401 with all 3 SEC-03 headers present (`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`). Note: host port is 9090 (not 8080 — port 8080 held by unrelated `dealflow_gateway` container; docker-compose.full-stack.yml:155 maps `9090-9091:9090`). Headers present on 401 as required by plan success criterion.

### 2. Cold Start Smoke — Next.js CSP Report-Only header
expected: Start Next.js dev server (`cd frontend && npm run dev`, runs on port 3100 per CLAUDE.md memory) → `curl -sI http://localhost:3100/ | grep -i content-security-policy` returns `Content-Security-Policy-Report-Only: default-src 'self'; script-src 'self' 'unsafe-inline' https://js.stripe.com ...`
result: pass
verified: Started `npm run dev` with `PORT=3100 NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8085/realms/jtoye-dev NEXT_PUBLIC_API_URL=http://localhost:9090`. Live curl returned 4 headers: (a) `Content-Security-Policy-Report-Only` with full directive set including `'unsafe-eval'` (dev form per isDev flag), Stripe 4-directive allowlist, Keycloak form-action, frame-ancestors 'none', base-uri 'self', object-src 'none', upgrade-insecure-requests; (b) `X-Content-Type-Options: nosniff`; (c) `Referrer-Policy: strict-origin-when-cross-origin`; (d) `Permissions-Policy: camera=(), microphone=(), geolocation=(), browsing-topics=()`.

### 3. CSP directive correctness — Stripe + Keycloak + frame-ancestors
expected: Read the `Content-Security-Policy-Report-Only` value from test 2 (or from `frontend/next.config.mjs` lines around `cspDirectives`). It should contain: `script-src` with `https://js.stripe.com https://*.js.stripe.com`, `frame-src` with `https://js.stripe.com https://hooks.stripe.com`, `form-action` with the Keycloak origin (NEXT_PUBLIC_KEYCLOAK_URL), `frame-ancestors 'none'`
result: pass
verified: Static grep of `frontend/next.config.mjs` lines 13-21 confirms: line 14 `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ''} https://js.stripe.com https://*.js.stripe.com`; line 19 `frame-src https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com`; line 20 `frame-ancestors 'none'`; line 21 `form-action 'self' ${keycloakOrigin}` with `keycloakOrigin = process.env.NEXT_PUBLIC_KEYCLOAK_URL`. Live Test 2 output confirms runtime composition matches.

### 4. Java SecurityHeaders suite green
expected: `./gradlew :core-java:test -PincludeIntegration --tests "*SecurityHeaders*"` → BUILD SUCCESSFUL, 8 tests across SecurityHeadersIntegrationTest (4), SecurityHeadersProdProfileTest (2), SecurityHeadersDevProfileTest (2)
result: pass
verified: BUILD SUCCESSFUL in 2m 45s with `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`. 5 actionable tasks (1 executed, 4 up-to-date). `System.out` teardown errors from testcontainer postgres shutdown at port 32801 are noise (HikariDataSource shutdown sequence). No test failures. Note: environment `JAVA_HOME` defaulted to JDK 25.0.2 which breaks gradle bootstrap before the toolchain directive kicks in; passing `JAVA_HOME=<jdk21>` inline works. Pre-existing env issue, not Phase 12 regression — see Gaps section for remediation options.

### 5. Frontend Jest CI-mode suite green
expected: `cd frontend && npm test -- --ci --watchAll=false` → PASS, 15 suites, 84 tests, 1 snapshot (exit 0 — same command CI runs per task 12-02-06)
result: pass
verified: 15 suites passed, 84 tests passed, 1 snapshot passed, runtime 2.768s, exit 0. Pre-existing `act()` warnings in `app/dashboard/kitchen/page.tsx` (line 191 setOrdersMap) noted — unrelated to Phase 12, already documented in executor summary.

### 6. Playwright CSP spec enumerates
expected: `cd frontend && npx playwright test e2e/csp-no-violations.spec.ts --list` → lists 6 tests (3 source tests × 2 projects: mobile + desktop). No execution required — structure check only.
result: pass
verified: "Total: 6 tests in 1 file" — `[mobile]` and `[desktop]` each running 3 tests (homepage CSP header + no violations, storefront `/shop/[slug]` no violations, dashboard 401-or-CSP check). Matches 12-02-SUMMARY.md claim.

### 7. CI workflow wiring — npm test after npm run build
expected: Inspect `.github/workflows/ci-cd.yaml`. The `Run frontend Jest tests` step (running `cd frontend && npm test -- --ci --watchAll=false`) appears AFTER the existing `Run frontend build` step in the test/frontend-build job. Build-first ordering preserved.
result: pass
verified: `.github/workflows/ci-cd.yaml` lines 75-76 = `Run frontend build` → `npm run build`; lines 79-80 = `Run frontend Jest tests` → `npm test -- --ci --watchAll=false`. Build-first ordering preserved. No other intervening steps between build and test.

### 8. middleware.ts untouched
expected: `git diff main..HEAD -- frontend/middleware.ts` returns NO output. The existing 4-line NextAuth `auth` re-export stays exactly as it was before Phase 12.
result: pass
verified: `git diff main..HEAD -- frontend/middleware.ts` returns empty output. The Next.js 16 middleware.ts deprecation scope creep was explicitly avoided per RESEARCH.md Q5 default.

### 9. Header snapshot files committed to git
expected: `git ls-tree HEAD -- core-java/src/test/resources/security-headers-snapshot.txt frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap` returns both paths tracked. Either would otherwise be missing and snapshot regression would silently pass.
result: pass
verified: Both paths tracked — `core-java/src/test/resources/security-headers-snapshot.txt` (blob 49e95bc0...) and `frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap` (blob 9cc86a77...).

### 10. Manual gate 12-02-07 documented with verification checklist
expected: `.planning/phases/12-spring-security-response-headers-frontend-csp/12-02-SUMMARY.md` has a visible section covering the 7-step human verification sequence for the Report-Only → enforce cutover (staging Report-Only confirmation, Playwright spec against staging, Stripe 3DS live test, NextAuth signin live test, ≥1-week observation, header key flip + snapshot regen, post-enforce re-verify)
result: pass
verified: `12-02-SUMMARY.md` lines 199-207 enumerate the 7 steps verbatim: (1) curl staging Report-Only check, (2) Playwright against staging with PLAYWRIGHT_BASE_URL, (3) Stripe 3DS test card 4000 0027 6000 3184, (4) NextAuth→Keycloak signin flow, (5) ≥1-week observation window with allowlist extension + `-u` snapshot regen, (6) header key flip to `Content-Security-Policy` + snapshot regen + commit together, (7) post-enforce re-verify steps 3+4.

## Summary

total: 10
passed: 10
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none from Phase 12 scope]

## Out-of-scope notes (surfaced during UAT, not regressions)

- **Environment JAVA_HOME mismatch** — shell `JAVA_HOME=/home/sanmi/.jdks/openjdk-25.0.2` breaks `./gradlew` bootstrap before the `build.gradle.kts:java.toolchain.languageVersion = 21` directive can kick in, even though `/usr/lib/jvm/jdk-21.0.6-oracle-x64` is installed. Remediation options (future, NOT Phase 12 scope):
  - Add `org.gradle.java.home=/usr/lib/jvm/jdk-21.0.6-oracle-x64` to `gradle.properties` (per-user or per-repo)
  - Enable gradle toolchain auto-provisioning via `org.gradle.java.installations.auto-download=true`
  - Document the required `JAVA_HOME` override in `CLAUDE.md` or a `.envrc`
- **Pre-existing `act()` warning** in `frontend/app/dashboard/kitchen/page.tsx:191` — React 19 stricter warning about `setOrdersMap` called outside `act()`. Pre-dates Phase 12 (Plan 12-02 executor called out as unrelated), not a UAT gap.
- **`frontend/.env.local.example`** has pre-existing uncommitted modification from v2.2 scoping (blocked by `block-secrets` pre-commit hook). Tracked in STATE.md pending todos; not a Phase 12 artifact.
