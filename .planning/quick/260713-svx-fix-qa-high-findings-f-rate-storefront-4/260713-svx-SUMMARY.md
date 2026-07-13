---
phase: quick-260713-svx
plan: 01
subsystem: storefront / dev-docs
tags: [f-rate, f-docs, rate-limit, 429, credentials, keycloak, docs-freshness]
requires: []
provides:
  - "public-fetch-retry helper (isRateLimitError / getRetryDelayMs / retry budget constants)"
  - "429-aware busy/retry state on /shop and /shop/[slug]"
  - "credential recipes that mint a Core-accepted token (env-var-only, confidential client)"
affects:
  - frontend/app/shop/page.tsx
  - frontend/app/shop/[slug]/page.tsx
  - docs/guides/TESTING.md
  - docs/guides/QA_TEST_PLAN.md
tech-stack:
  added: []
  patterns:
    - "framework-agnostic axios-error helper unit-tested in isolation"
    - "bounded exponential backoff honouring server Retry-After, ref-held retry timer with unmount cleanup"
key-files:
  created:
    - frontend/lib/public-fetch-retry.ts
    - frontend/lib/__tests__/public-fetch-retry.test.ts
    - frontend/__tests__/shop/rate-limit.test.tsx
  modified:
    - frontend/app/shop/page.tsx
    - frontend/app/shop/[slug]/page.tsx
    - README.md
    - docs/guides/TESTING.md
    - docs/guides/QA_TEST_PLAN.md
    - docs/setup/SETUP.md
    - docs/guides/QUICK_START.md
    - docs/metrics.json
    - CLAUDE.md
decisions:
  - "docs/config/CREDENTIALS.md does not exist at this commit — its designated content (fixed token recipe + lockout note) was routed into TESTING.md (canonical) and cross-referenced from QA_TEST_PLAN.md"
  - "admin123 was also stale (admin-user shares KC_SEED_USER_PASSWORD per the plan interface) — replaced alongside password123 as a correctness fix"
metrics:
  duration: ~35min
  completed: 2026-07-13
requirements: ["F-RATE", "F-DOCS-1", "#88"]
---

# Phase quick-260713-svx Plan 01: QA High findings (F-RATE + F-DOCS-1) Summary

429-aware storefront retry helper wired into both public shop surfaces so a public-API HTTP 429 renders a transient "busy / retrying" state instead of the authoritative empty catalogue, plus credential-doc recipes repaired to reference env vars only and actually mint a Core-accepted token.

## What was built

### F-RATE (#88) — 429 no longer collapses the storefront to "empty"
- New pure, framework-agnostic helper `frontend/lib/public-fetch-retry.ts`:
  - `isRateLimitError(error)` — true only for `error.response.status === 429` (network errors / 404 / 500 / null all false).
  - `getRetryDelayMs(error, attempt)` — honours a positive `Retry-After` (seconds → ms) clamped to `MAX_DELAY_MS` (10s); otherwise capped exponential backoff `min(800 * 2**attempt, 10_000)`.
  - Exported budget constants `MAX_RETRY_ATTEMPTS = 4`, `BASE_DELAY_MS = 800`, `MAX_DELAY_MS = 10_000`.
- `/shop` list (`app/shop/page.tsx`): a 429 catch now sets a `rateLimited` state, schedules a bounded auto-retry (ref-held timer, cleared on unmount), and renders a "High demand right now — retrying automatically…" block that takes precedence over the `shops.length === 0` "No shops found" empty state. When the retry budget is exhausted it swaps the auto-retry line for a manual "Try again" button. A non-429 error preserves the existing `setShops([])` empty behaviour; a genuine empty 200 still renders "No shops found".
- `/shop/[slug]` detail (`app/shop/[slug]/page.tsx`): `load()` refactored to a `useCallback` with the same bounded-retry mechanism; a 429 on the critical shop/products calls now drives a busy/retry branch placed BEFORE the `if (!shop)` "Shop not found" block, so a 429 can never fall through to it. The 4 optional calls (reviews/config/promotions/announcements) still `.catch()` to defaults. Busy blocks render static copy only — never `error.message` or the raw response body (threat T-svx-02).

### F-DOCS-1 — credential recipes repaired
- Eliminated every hard-coded `password123` (and stale `admin123`) across README.md, docs/setup/SETUP.md, docs/guides/QUICK_START.md, docs/guides/TESTING.md, docs/guides/QA_TEST_PLAN.md; seed users now reference `KC_SEED_USER_PASSWORD`.
- Added `-d "client_secret=$KEYCLOAK_CLIENT_SECRET"` to every `core-api` password-grant (ROPC) recipe in TESTING.md (7 recipes) and QA_TEST_PLAN.md (2 recipes) — the confidential `core-api` client returns `unauthorized_client` without it.
- Added a seed-user lockout-recovery note (realm `bruteForceProtected=true`, issue #87) to TESTING.md (canonical) with a Keycloak-admin-console unlock recipe, cross-referenced from QA_TEST_PLAN.md; both reference `KC_SEED_USER_PASSWORD` and note that `core-api` is a confidential client.

### Docs-freshness reconciliation
- `scripts/docs-freshness.sh --write` regenerated `docs/metrics.json`: `jest_blocks` 234→249, `jest_files` 33→35, `total_logical_invocations` 1243→1258.
- CLAUDE.md testing-standard prose synced to the new totals (1243→1258, 234→249, 33→35 files).

## Tasks & commits

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 (RED) | failing tests for 429-aware retry | `38dc534` |
| 1 (GREEN) | helper + wire both storefront surfaces | `da6a741` |
| 2 | repair credential recipes + lockout note | `3b195e5` |
| 3 | reconcile metrics.json + CLAUDE.md | `bcf7fcc` |

## Verification

- New tests: `npx jest lib/__tests__/public-fetch-retry.test.ts __tests__/shop/rate-limit.test.tsx` → **2 suites / 15 tests pass**.
- Full frontend suite (regression guard for the page edits): **35 suites / 244 tests pass**.
- `npm run build` (Turbopack, tsc typecheck) → **clean** (TypeScript finished in 6.5s, all routes compile).
- `grep -rn password123` across the five existing target docs → **zero** (also zero `admin123`).
- `scripts/docs-freshness.sh` (check mode) → **docs-freshness OK (total logical invocations: 1258)**.
- Secret-scan sanity: doc diffs introduce only env-var NAMES, no literal secret values (gitleaks not installed locally; manual grep clean). CI gitleaks/GitGuardian will run on the PR.

Note: the docs-freshness "logical invocations" is a static `\b(it|test)\(` grep count owned by the script (249 blocks), which differs by design from the 244 runtime-executed Jest cases. The script is the arbiter per project convention; CLAUDE.md/metrics.json track the static count.

## Deviations from Plan

### [Rule 3 — Plan premise inaccurate] docs/config/CREDENTIALS.md does not exist
- **Found during:** Task 2.
- **Issue:** The plan named `docs/config/CREDENTIALS.md` as a fix site and the "canonical home" for the token recipe + lockout note, but the file does not exist at base commit `05cf571` (and contained no `password123`). Its cited line numbers (test-client recipe ~99-126, `core-api-secret-2026` ~76) did not match any file — those literals are absent repo-wide.
- **Fix:** Did NOT invent a new CREDENTIALS.md (would risk doc drift, against the Incremental Betterment Doctrine). Routed its intended content — the fixed confidential-client token recipe and the bruteForceProtected lockout-recovery note — into `docs/guides/TESTING.md` (the canonical doc that actually holds the ROPC recipes), cross-referenced from `docs/guides/QA_TEST_PLAN.md`. The plan's `grep -l KC_SEED_USER_PASSWORD/KEYCLOAK_CLIENT_SECRET ... CREDENTIALS.md` verify was adapted to the two files that exist (TESTING.md + QA_TEST_PLAN.md), both of which now contain the env-var references.

### [Rule 2 — correctness] admin123 was also stale
- **Found during:** Task 2.
- **Issue:** The user tables carried a hard-coded `admin123` for `admin-user`. Per the plan's interface note, `KC_SEED_USER_PASSWORD` renders the password for all three seed users including `admin-user`, so `admin123` is a dead/secret-shaped literal identical in kind to `password123`.
- **Fix:** Replaced `admin123` with a `KC_SEED_USER_PASSWORD` reference in the same edits (QUICK_START, TESTING table, QA_TEST_PLAN table).

## Deferred / operator follow-ups (not implemented here)

- **Backend limiter tuning (#88):** raising / bucketing the public per-IP rate limit (burst-20) is OUT OF SCOPE for this frontend fix and remains a backend follow-up. Each `/shop/[slug]` view still fires ~6 parallel `/public/*` calls against the shared IP bucket; the frontend now degrades gracefully, but the underlying limit may warrant a dedicated public-read budget.
- **Live seed-user unlock (operator action):** the live `tenant-a-user` may already be locked from the QA-council run 20260713-152124 (stale `password123` replays under `bruteForceProtected=true`). One-time recovery: Keycloak admin console → realm `jtoye-dev` → Users → tenant-a-user → **Unlock user**. This is an operator/config action, not a code change; the recovery note is now documented in TESTING.md.

## Execution note (non-code, transient)

- The worktree had no `frontend/node_modules`. Jest/build were run against a gitignored local copy of the shared checkout's `node_modules` (a plain symlink tripped Turbopack's "symlink points out of filesystem root" guard, so it was replaced with a fast `cp -al` hardlink copy). Nothing under `node_modules` is tracked or committed.

## Self-Check: PASSED

- All 3 created files exist on disk; all 5+2 modified files present.
- All 4 task commits (`38dc534`, `da6a741`, `3b195e5`, `bcf7fcc`) exist in git history.
- Working tree clean (only the uncommitted SUMMARY remains, per orchestrator handoff).

## TDD Gate Compliance

- RED commit `38dc534` (`test(...)`, both suites failing — helper/wiring absent) precedes GREEN commit `da6a741` (`feat(...)`, 15/15 green). No REFACTOR commit needed. Gate sequence satisfied.
