---
phase: 12-spring-security-response-headers-frontend-csp
plan: 02
subsystem: frontend-security
tags: [nextjs, csp, content-security-policy, stripe, nextauth, keycloak, jest, playwright, ci]

# Dependency graph
requires:
  - phase: 12-01-spring-security-response-headers
    provides: Spring Security side ASVS 14.4.x headers (X-Frame-Options, nosniff, Referrer-Policy, profile-gated HSTS) — the Java side of the cross-tier header baseline this plan mirrors on Next.js
provides:
  - Content-Security-Policy-Report-Only on all Next.js responses (SEC-02) covering Stripe (3DS, JS, XHR), NextAuth → Keycloak form-action, API + WS connect-src, MinIO/S3 img-src
  - X-Content-Type-Options: nosniff on Next.js (frontend mirror of Spring 12-01 guarantee)
  - Referrer-Policy: strict-origin-when-cross-origin on Next.js
  - Permissions-Policy: camera=(), microphone=(), geolocation=(), browsing-topics=() (Q7 default)
  - Jest CI gate failing on header regression (csp-headers.test.ts + header-snapshot.test.ts wired via --ci mode)
  - Playwright CSP-violation smoke spec for local/staging Report-Only observation (NOT in CI)
  - playwright.config.ts baseURL parameterized via PLAYWRIGHT_BASE_URL (resolves port 3000/3100 mismatch)
affects: [13-api-gateway-headers, any phase editing next.config.mjs or adding a new cross-origin integration]

# Tech tracking
tech-stack:
  added: []  # No new libraries — Next.js built-in headers() API + existing Jest/Playwright stack
  patterns:
    - "Next.js 16 async headers() in next.config.mjs (NOT middleware.ts — deprecated in Next 16)"
    - "Env-driven CSP directives: NEXT_PUBLIC_KEYCLOAK_URL → form-action; NEXT_PUBLIC_API_URL → connect-src + derived wss:// origin"
    - "parseCsp() per-directive helper guards against regressions that drop Stripe from frame-src while leaving it in script-src (string-contains false-pass defence)"
    - "jest.resetModules() + dynamic await import('../next.config.mjs') inside each test for ESM-safe env-var-dependent config reload"
    - "Jest .toMatchSnapshot() with deterministic env (NODE_ENV=production, fixed Keycloak/API origins) sorted-by-key for reproducible drift detection"
    - "Two-phase CSP rollout: Content-Security-Policy-Report-Only merges in this plan; human gate (Task 07) flips header key to Content-Security-Policy enforce after 1-week staging observation"
    - "CI gate via npm test -- --ci --watchAll=false (--ci fails on missing snapshot rather than writing one)"

key-files:
  created:
    - frontend/__tests__/csp-headers.test.ts (7 it() cases covering header presence, baseline directives, per-directive Stripe allowlist, Keycloak form-action, wss:// derivation, baseline nosniff/Referrer-Policy/Permissions-Policy)
    - frontend/__tests__/header-snapshot.test.ts (1 toMatchSnapshot() case with deterministic env)
    - frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap (golden snapshot committed to git)
    - frontend/e2e/csp-no-violations.spec.ts (3 test() cases × mobile + desktop projects = 6 enumerated Playwright tests; LOCAL/STAGING only, NOT in CI)
  modified:
    - frontend/next.config.mjs (additive — cspDirectives const + async headers() function; preserved output: 'standalone' and images.remotePatterns verbatim)
    - frontend/playwright.config.ts (3-line change — use.baseURL now reads process.env.PLAYWRIGHT_BASE_URL with localhost:3000 fallback + inline comment)
    - .github/workflows/ci-cd.yaml (4-line additive — new "Run frontend Jest tests" step after the existing "Run frontend build" step in the test job)

key-decisions:
  - CSP ship via next.config.mjs `async headers()` (not middleware.ts — deprecated in Next 16; not proxy.ts — scope creep per RESEARCH.md Q5)
  - Report-Only rollout in this merge; Task 12-02-07 is the human gate that flips to enforce after ≥1-week staging observation
  - `'unsafe-inline'` retained in script-src (Next.js hydration requirement per RESEARCH.md §5.3) — ASVS L1 accepted trade-off; nonce-based CSP deferred to ASVS L2+ follow-up phase (preserves ISR/SSG on storefront)
  - Per-directive parseCsp() assertion in Test 4 — catches the regression class where Stripe is dropped from frame-src (breaking 3DS iframes) while still appearing elsewhere in the CSP string
  - Jest is THE CI gate (Task 06); Playwright CSP spec is local/staging only (RESEARCH.md §6 Q4 default — avoids hosting a live Next server in CI for a smoke check covered better by staging)
  - Snapshot fixes NODE_ENV=production so the snapshot captures the production CSP form without 'unsafe-eval' in script-src (dev form is less rigid and not worth snapshotting)
  - Permissions-Policy ADDED as extra defence (RESEARCH.md Q7 default — low cost) alongside the core CSP / nosniff / Referrer-Policy trio
  - Port 3000/3100 mismatch resolved via PLAYWRIGHT_BASE_URL env var; defaulting to 3000 preserves existing CI + all existing specs that hardcode that origin

patterns-established:
  - "Next.js headers() Jest unit test pattern: dynamic import + jest.resetModules() + per-test env var mutation — reusable for any future config-driven header logic"
  - "parseCsp(value) helper for per-directive assertions — prevents substring false-passes on CSP regressions"
  - "Golden JSON .snap file + --ci flag in CI for header regression catch — frontend mirror of the Java security-headers-snapshot.txt pattern established in Plan 12-01"
  - "Two-phase CSP rollout with Report-Only merge → manual staging-gate cutover — template for any future CSP tightening (e.g. nonce-based CSP L2+)"

metrics:
  duration_seconds: 283
  duration_human: "~5 minutes (Claude autonomous execution window)"
  completed: 2026-04-18
  tasks_executed: 6  # Tasks 01-06 autonomous; Task 07 is a manual gate pending human verification
  commits: 6
  jest_tests_added: 8  # 7 in csp-headers.test.ts + 1 in header-snapshot.test.ts
  playwright_tests_added: 3  # 3 cases × 2 projects = 6 enumerated, but 3 unique source tests
  files_created: 4
  files_modified: 3
---

# Phase 12 Plan 02: Next.js CSP + CI wiring + manual cutover gate Summary

Ship Next.js 16 Content-Security-Policy-Report-Only via `next.config.mjs` `async headers()` (NOT middleware.ts, which is deprecated in Next 16), wire the header regression Jest suite into `.github/workflows/ci-cd.yaml` as the CI gate for SEC-02 criterion 5, and leave the Report-Only → enforce cutover as a human-verified manual gate after ≥1-week staging observation — closing the XSS / clickjacking / Stripe-3DS / NextAuth-signin browser attack surface (T-12-05..08) without breaking Stripe Elements, Keycloak redirect, storefront rendering, or KDS WebSocket connections.

## Execution overview

| Task | Type | Status | Commit | Description |
| ---- | ---- | ------ | ------ | ----------- |
| 12-02-01 | auto (TDD RED) | done | `9163143` | Create `frontend/__tests__/csp-headers.test.ts` with 7 it() cases — all fail with `TypeError: config.headers is not a function` (expected RED) |
| 12-02-02 | auto (TDD GREEN) | done | `0a19c4c` | Add `cspDirectives` const + `async headers()` to `frontend/next.config.mjs` — all 7 Jest tests pass |
| 12-02-03 | auto (TDD, snapshot) | done | `fddbc4e` | Add `frontend/__tests__/header-snapshot.test.ts` + committed `.snap` file — first run writes snapshot, second run passes unchanged |
| 12-02-04 | auto | done | `445f169` | Parameterize `frontend/playwright.config.ts` baseURL via `PLAYWRIGHT_BASE_URL` env var (fallback `http://localhost:3000`) |
| 12-02-05 | auto | done | `30d94ee` | Create `frontend/e2e/csp-no-violations.spec.ts` (3 test cases, LOCAL/STAGING only) — `npx playwright test --list` enumerates 6 tests across mobile + desktop projects |
| 12-02-06 | auto | done | `8baf065` | Insert `Run frontend Jest tests` step (npm test -- --ci --watchAll=false) into `.github/workflows/ci-cd.yaml` AFTER `npm run build`, BEFORE `Upload test results` |
| 12-02-07 | checkpoint:human-verify | **pending** | — | Report-Only → enforce cutover after ≥1-week staging observation + Stripe 3DS + NextAuth signin verification |

**Plan status: operationally complete (Tasks 01-06 done); cutover (Task 07) pending human verification.**

## Final CSP directive string (Report-Only form, emitted to staging/prod on merge)

CSP value as committed in `next.config.mjs` with `NEXT_PUBLIC_KEYCLOAK_URL=https://keycloak.snapshot.local`, `NEXT_PUBLIC_API_URL=https://api.snapshot.local`, `NODE_ENV=production`:

```
default-src 'self';
script-src 'self' 'unsafe-inline' https://js.stripe.com https://*.js.stripe.com;
style-src 'self' 'unsafe-inline';
img-src 'self' data: blob: https://*.stripe.com https: http://localhost:9000;
font-src 'self' data:;
connect-src 'self' https://api.stripe.com https://*.stripe.com https://api.snapshot.local wss://api.snapshot.local https://keycloak.snapshot.local;
frame-src https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com;
frame-ancestors 'none';
form-action 'self' https://keycloak.snapshot.local;
base-uri 'self';
object-src 'none';
upgrade-insecure-requests
```

Header key on merge: `Content-Security-Policy-Report-Only`. Manual gate (Task 07) flips to `Content-Security-Policy` after observation window.

Additional headers on every Next.js response (sorted by key in snapshot):

| Key | Value |
| --- | ----- |
| Content-Security-Policy-Report-Only | (above) |
| Permissions-Policy | `camera=(), microphone=(), geolocation=(), browsing-topics=()` |
| Referrer-Policy | `strict-origin-when-cross-origin` |
| X-Content-Type-Options | `nosniff` |

## Test suite additions

**Jest (wired into CI via Task 06):**

- `frontend/__tests__/csp-headers.test.ts` — 7 cases:
  1. `returns a single route matching all paths` (`/:path*` shape)
  2. `emits a Content-Security-Policy or Content-Security-Policy-Report-Only header`
  3. `has baseline directives` (default-src, frame-ancestors, base-uri, object-src)
  4. `allowlists Stripe in the correct directives (script-src, frame-src, connect-src)` — per-directive parseCsp assertion
  5. `form-action includes the configured Keycloak origin` (env-driven)
  6. `connect-src derives wss:// origin from NEXT_PUBLIC_API_URL` (env-driven)
  7. `emits X-Content-Type-Options nosniff, Referrer-Policy, and Permissions-Policy headers`
- `frontend/__tests__/header-snapshot.test.ts` — 1 case (`matches snapshot`) against committed `.snap`

Full Jest run on post-merge branch state: **15 suites, 84 tests, 1 snapshot — all pass**, exit 0.

**Playwright (LOCAL/STAGING only — NOT in CI):**

- `frontend/e2e/csp-no-violations.spec.ts` — 3 source tests:
  1. `homepage emits CSP header and triggers no violations`
  2. `storefront /shop/[slug] triggers no CSP violations`
  3. `dashboard route either 401s (expected without session) or emits CSP with no violations`

Enumerated via `npx playwright test --list` as 6 tests (3 × mobile + desktop projects) with exit 0.

## CI pipeline additions

`.github/workflows/ci-cd.yaml` — test job now runs:

```yaml
      - name: Run frontend build (validates TypeScript)
        run: npm run build
        working-directory: frontend

      - name: Run frontend Jest tests
        run: npm test -- --ci --watchAll=false
        working-directory: frontend
```

Flag rationale:
- `--ci` — Jest CI mode: fails on missing snapshot instead of writing it (catches drift without explicit `-u` regeneration)
- `--watchAll=false` — belt-and-braces; no interactive watch even if a future jest.config default flips

Step ordering verified: build at line 76, test at line 80 — build-first ordering preserved.

## Keycloak / API / WS origins in the final allowlist

| Directive | Origins (from env at build time) |
| --- | --- |
| `form-action` | `'self'`, `${NEXT_PUBLIC_KEYCLOAK_URL}` |
| `connect-src` | `'self'`, `https://api.stripe.com`, `https://*.stripe.com`, `${NEXT_PUBLIC_API_URL}`, derived `wss://` origin, `${NEXT_PUBLIC_KEYCLOAK_URL}` |
| `script-src` | `'self'`, `'unsafe-inline'`, `'unsafe-eval'` (dev only), `https://js.stripe.com`, `https://*.js.stripe.com` |
| `frame-src` | `https://js.stripe.com`, `https://*.js.stripe.com`, `https://hooks.stripe.com` |
| `img-src` | `'self'`, `data:`, `blob:`, `https://*.stripe.com`, `https:`, `http://localhost:9000` (MinIO dev) |

## Deviations from Plan

None — plan executed exactly as written. No auto-fixes (Rule 1/2/3) triggered; no architectural escalations (Rule 4) required.

**Scope-respected out-of-scope items (NOT touched this plan, logged here for visibility):**
- `frontend/middleware.ts` — unchanged per RESEARCH.md Q5 (`middleware.ts` → `proxy.ts` rename is a separate Next.js 16 migration concern)
- `frontend/lib/__tests__/api-client-interceptors.test.ts` — reports pre-existing `act()` warnings on `app/dashboard/kitchen/page.tsx` setState calls (lines 189-191); unrelated to this plan's changes, not auto-fixed (Scope Boundary rule)

## Authentication gates

None — plan fully autonomous through Task 06; Task 07 is a design-level human-verify gate, not an auth gate.

## Known Stubs

None. All shipped code is production-grade. The Report-Only header key is an intentional rollout-phase state documented in the header value comment + Task 07 verification steps — not a stub.

## Threat Flags

None. No new trust boundary introduced; the plan adds defence-in-depth headers on existing Browser ↔ Next.js server boundary that was already in the 12-RESEARCH.md threat register.

## Operational gate — Task 12-02-07 (pending human verification)

**Plan status: operationally complete. Executable work done on feature branch `feature/phase-12-security-headers-csp`. Cutover to `Content-Security-Policy` enforce header awaits human verification per plan Task 12-02-07.**

Next steps for the human operator (see full spec in `12-02-PLAN.md` Task 07):

1. Confirm Report-Only active in staging: `curl -sI https://staging.jtoye.co.uk/ | grep -i content-security-policy`
2. Run Playwright CSP spec against staging: `PLAYWRIGHT_BASE_URL=https://staging.jtoye.co.uk npx playwright test e2e/csp-no-violations.spec.ts`
3. Drive Stripe 3DS flow (test card `4000 0027 6000 3184`) on staging checkout, verify no `[Report Only] Refused to load ...` errors in DevTools console and test charge completes
4. Drive NextAuth signin → Keycloak → back-to-app flow, verify no form-action refusal
5. Observation window: ≥1 week in staging with Report-Only active; track violations in a scratch file and extend the CSP allowlist per hit (`npm test -- -u header-snapshot` to regenerate snapshot after each edit)
6. Flip key: in `frontend/next.config.mjs`, change `Content-Security-Policy-Report-Only` → `Content-Security-Policy`, run `npm test -- __tests__/header-snapshot.test.ts -u` to regenerate the snapshot for the enforce form, commit both files in one feature-branch commit, open PR
7. Post-enforce verification: re-run steps 3 + 4 against staging after the enforce PR merges; revert if either flow breaks in enforce mode that worked in Report-Only mode

## Self-Check: PASSED

**Files created (all confirmed present on disk):**
- FOUND: `frontend/__tests__/csp-headers.test.ts`
- FOUND: `frontend/__tests__/header-snapshot.test.ts`
- FOUND: `frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap`
- FOUND: `frontend/e2e/csp-no-violations.spec.ts`

**Files modified (all confirmed present on disk with expected content):**
- FOUND: `frontend/next.config.mjs` (`grep -c "async headers()"` = 1)
- FOUND: `frontend/playwright.config.ts` (`grep -c "PLAYWRIGHT_BASE_URL"` = 1)
- FOUND: `.github/workflows/ci-cd.yaml` (`grep -c "Run frontend Jest tests"` = 1)

**Commits (all verified in `git log --oneline`):**
- FOUND: `9163143` — test(phase-12-02): task 12-02-01 — RED Jest CSP header tests
- FOUND: `0a19c4c` — feat(phase-12-02): task 12-02-02 — headers() + Report-Only CSP in next.config.mjs (GREEN)
- FOUND: `fddbc4e` — test(phase-12-02): task 12-02-03 — Jest snapshot regression test + .snap
- FOUND: `445f169` — chore(phase-12-02): task 12-02-04 — parameterize playwright.config.ts baseURL
- FOUND: `30d94ee` — test(phase-12-02): task 12-02-05 — Playwright CSP-violation spec (local/staging)
- FOUND: `8baf065` — ci(phase-12-02): task 12-02-06 — wire frontend Jest suite into ci-cd.yaml

**Verification commands:**
- `cd frontend && npm test -- __tests__/csp-headers.test.ts` → 7 passed, exit 0
- `cd frontend && npm test -- __tests__/header-snapshot.test.ts` → 1 passed, 1 snapshot, exit 0
- `cd frontend && npm test -- --ci --watchAll=false` → 15 suites, 84 tests, 1 snapshot — all pass, exit 0 (CI gate will pass)
- `cd frontend && npx playwright test e2e/csp-no-violations.spec.ts --list` → 6 tests enumerated (3 × 2 projects), exit 0
- `cd frontend && npx playwright test --list` → 36 tests enumerated across full suite, exit 0

## TDD Gate Compliance

Plan-level TDD cycle across Tasks 01 → 02 → 03 executed cleanly:

- **RED** (Task 01, commit `9163143`): `test(phase-12-02): task 12-02-01 — RED Jest CSP header tests` — 7 failing tests committed first
- **GREEN** (Task 02, commit `0a19c4c`): `feat(phase-12-02): task 12-02-02 — headers() + Report-Only CSP ... (GREEN)` — implementation after RED; all 7 tests pass
- **REFACTOR**: Not needed — GREEN code is already concise (41 lines, additive) and passes lint via TypeScript build; no cleanup step required

Gate sequence fully satisfied.
