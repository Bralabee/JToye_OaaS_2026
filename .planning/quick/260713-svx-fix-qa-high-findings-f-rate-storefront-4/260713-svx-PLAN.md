---
phase: quick-260713-svx
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/lib/public-fetch-retry.ts
  - frontend/lib/__tests__/public-fetch-retry.test.ts
  - frontend/app/shop/page.tsx
  - frontend/app/shop/[slug]/page.tsx
  - frontend/__tests__/shop/rate-limit.test.tsx
  - README.md
  - docs/config/CREDENTIALS.md
  - docs/guides/TESTING.md
  - docs/guides/QA_TEST_PLAN.md
  - docs/setup/SETUP.md
  - docs/guides/QUICK_START.md
  - docs/metrics.json
  - CLAUDE.md
autonomous: true
requirements: ["F-RATE", "F-DOCS-1", "#88"]

must_haves:
  truths:
    - "When GET /public/shops returns HTTP 429, the /shop page shows a distinguishable transient 'busy / retrying' state — NEVER the authoritative 'No shops found' empty state"
    - "When GET /public/shops/{slug} (or its /products) returns HTTP 429, the /shop/[slug] page shows a 'busy / retrying' state — NEVER the authoritative 'Shop not found' empty state"
    - "A 429 triggers an automatic bounded retry with backoff that honours the server Retry-After header when present; a genuine 200-with-empty-content still renders the real empty state"
    - "No documentation credential recipe hard-codes the string password123; password-grant recipes reference the KC_SEED_USER_PASSWORD env var instead"
    - "The credential-grant recipes use a client + client_secret that mints a Core-accepted token (no test-client aud:null 401, no core-api unauthorized_client from a missing client_secret)"
    - "Docs include a tenant-a-user brute-force lockout recovery note"
    - "scripts/docs-freshness.sh passes: docs/metrics.json matches source reality after the new Jest tests, and CLAUDE.md's testing-standard prose is reconciled to the new totals"
  artifacts:
    - path: "frontend/lib/public-fetch-retry.ts"
      provides: "isRateLimitError() + getRetryDelayMs() pure helpers for 429 detection and backoff"
      exports: ["isRateLimitError", "getRetryDelayMs"]
    - path: "frontend/app/shop/page.tsx"
      provides: "429 busy/retry branch on the shop discovery list"
      contains: "isRateLimitError"
    - path: "frontend/app/shop/[slug]/page.tsx"
      provides: "429 busy/retry branch on the shop detail page"
      contains: "isRateLimitError"
    - path: "frontend/lib/__tests__/public-fetch-retry.test.ts"
      provides: "unit tests for 429 detection + backoff"
    - path: "frontend/__tests__/shop/rate-limit.test.tsx"
      provides: "component tests proving 429 → busy state (not empty) on both storefront surfaces"
    - path: "docs/metrics.json"
      provides: "reconciled test counts (docs-freshness gate)"
  key_links:
    - from: "frontend/app/shop/page.tsx"
      to: "frontend/lib/public-fetch-retry.ts"
      via: "import { isRateLimitError, getRetryDelayMs }"
      pattern: "isRateLimitError"
    - from: "frontend/app/shop/[slug]/page.tsx"
      to: "frontend/lib/public-fetch-retry.ts"
      via: "import { isRateLimitError, getRetryDelayMs }"
      pattern: "isRateLimitError"
    - from: "docs credential recipes"
      to: ".env.example / infra/.env.example"
      via: "KC_SEED_USER_PASSWORD + KEYCLOAK_CLIENT_SECRET env-var references"
      pattern: "KC_SEED_USER_PASSWORD"
---

<objective>
Close the two High-severity findings from QA-council run 20260713-152124.

- **F-RATE** (pre-existing #88): a public-API HTTP 429 currently collapses the storefront to an
  authoritative empty state — `/shop` renders "No shops found" (catch → `setShops([])`) and
  `/shop/[slug]` renders "Shop not found" (catch → `setShop(null)`). Each detail page fires ~6
  parallel `/public/*` calls against a burst-20 IP bucket, so ~3 quick page views empty the
  marketplace for a real browsing user. Fix: distinguish 429 from a real empty result and surface a
  transient "busy / retrying" state that auto-retries with backoff — never a definitive empty catalogue.
- **F-DOCS-1**: README + CREDENTIALS.md + TESTING.md + QA_TEST_PLAN.md (and the sibling SETUP.md /
  QUICK_START.md) ship dead credential recipes — `password123` password-grants (fail against the
  re-imported realm and brute-force-lock the live user), a structurally-dead `test-client` recipe
  (aud:null → Core 401), and a `core-api` ROPC recipe missing `client_secret` (→ unauthorized_client).
  Fix: point recipes at the `KC_SEED_USER_PASSWORD` / `KEYCLOAK_CLIENT_SECRET` env vars, repair or
  remove the dead grant recipes, and add a lockout-recovery note.

Purpose: a real customer must never see an empty marketplace caused by rate limiting, and every
documented dev-credential recipe must actually mint a Core-accepted token.
Output: a testable 429-aware retry helper wired into both storefront surfaces (with Jest tests),
corrected credential docs referencing env vars only, and a reconciled docs-freshness manifest.

Scope note (deferred, per task constraints): raising / bucketing the backend public per-IP limiter
(#88) is OUT OF SCOPE for this quick task — the fix here is the FRONTEND surfacing of 429. Record the
backend-limiter tuning as a deferred follow-up in the SUMMARY.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md

# F-RATE fix sites (both currently collapse a 429 into an authoritative empty state)
@frontend/app/shop/page.tsx
@frontend/app/shop/[slug]/page.tsx
@frontend/lib/public-api-client.ts

# F-DOCS fix sites
@docs/config/CREDENTIALS.md
@docs/guides/QA_TEST_PLAN.md

<interfaces>
<!-- Executor should use these directly — no codebase exploration needed. -->

Error shape (axios): a 429 arrives as a rejected error whose `error.response.status === 429` and
whose retry hint (set by the backend #88 public bucket) is `error.response.headers['retry-after']`
(seconds, string). Network failures / timeouts have NO `error.response`.

frontend/lib/public-api-client.ts (existing — unchanged by this plan):
  - default-exported axios instance, baseURL = process.env.NEXT_PUBLIC_API_URL
  - response interceptor rejects errors unchanged (no status transform), so the raw axios error
    (with .response.status / .response.headers) reaches each page's catch.

Jest wiring already in the repo (jest.config.js): testMatch covers `**/__tests__/**` and
`*.test.tsx`; `@/` maps to `frontend/`. Existing page-test mock pattern:
  - jest.mock('@/lib/public-api-client'); cast the default export as jest.Mocked
  - mockedClient.get.mockRejectedValue({ response: { status: 429, headers: { 'retry-after': '1' } } })

docs-freshness contract (scripts/docs-freshness.sh): jest_blocks counts occurrences of the
it/test call regex across `frontend/(app|components|lib|hooks|types|__tests__)/**/*.test.tsx?`.
Both new test paths (`frontend/lib/__tests__/…` and `frontend/__tests__/shop/…`) are inside that
regex. The script recomputes the manifest from git-tracked files with `--write`; do NOT hand-edit
the numbers.

Env-var facts (for the doc recipes — these are the REAL, rotated names, not literals):
- `KC_SEED_USER_PASSWORD` — renders the seed-user password (tenant-a-user / tenant-b-user / admin-user).
  Defined in `.env.example` (line ~110), `infra/.env.example`, all three docker-compose files, and
  `infra/keycloak/configure-keycloak.sh`.
- `KEYCLOAK_CLIENT_SECRET` — renders the confidential `core-api` client secret (`.env.example` ~line 102).
  The realm `core-api` client is confidential, so a working password-grant ROPC recipe MUST send both
  `client_id=core-api` AND `client_secret=$KEYCLOAK_CLIENT_SECRET`.
- The realm sets `bruteForceProtected=true` (issue #87), so replaying a wrong password (e.g. the stale
  `password123`) temporarily LOCKS the user — hence the required recovery note.
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: F-RATE — 429-aware retry helper + wire both storefront surfaces (with Jest tests)</name>
  <files>frontend/lib/public-fetch-retry.ts, frontend/lib/__tests__/public-fetch-retry.test.ts, frontend/app/shop/page.tsx, frontend/app/shop/[slug]/page.tsx, frontend/__tests__/shop/rate-limit.test.tsx</files>
  <behavior>
    Helper (frontend/lib/public-fetch-retry.ts — pure, no React):
    - `isRateLimitError(error): boolean` → true ONLY when `error?.response?.status === 429`.
      Test cases: 429 → true; 500 → false; 404 → false; a network error `{ message: 'Network Error' }`
      with no `response` → false; `undefined`/`null` → false.
    - `getRetryDelayMs(error, attempt): number` → if `error.response.headers['retry-after']` parses to a
      positive number of seconds, return that × 1000 clamped to MAX_DELAY_MS (10_000); else exponential
      backoff `min(BASE_DELAY_MS * 2**attempt, MAX_DELAY_MS)` with BASE_DELAY_MS = 800.
      Test cases: attempt 0 no header → 800; attempt 2 no header → 3200; `retry-after: '2'` → 2000;
      `retry-after: '999'` → clamped to 10_000; non-numeric `retry-after: 'later'` → falls back to backoff.
    - Export `MAX_RETRY_ATTEMPTS = 4` (consumed by the pages).

    Component behavior (frontend/__tests__/shop/rate-limit.test.tsx — use jest fake timers):
    - /shop list: mock `publicApiClient.get` to reject with `{ response: { status: 429, headers: {} } }`
      → after initial load, the page shows text matching /retrying/i and the string "No shops found" is
      NOT in the document. Then advance timers + flip the mock to resolve `{ data: { content: [], totalPages: 0 } }`
      → the busy copy disappears and the genuine "No shops found" empty state now renders (proves 429 ≠ empty).
    - /shop/[slug] detail: render with `params={Promise.resolve({ slug: 'x' })}` (unwrap the React 19
      `use(params)` — wrap the render in `<Suspense>` if needed), mock the client to reject 429
      → busy copy matching /retrying/i is shown and "Shop not found" is NOT in the document.
      Mock `useCart` from `@/components/storefront/cart-provider` defensively so the tree renders.
  </behavior>
  <action>
    Create `frontend/lib/public-fetch-retry.ts` exporting `isRateLimitError`, `getRetryDelayMs`,
    `MAX_RETRY_ATTEMPTS`, `BASE_DELAY_MS`, `MAX_DELAY_MS` exactly as described in <behavior>. Keep it
    framework-agnostic (no React imports) so it is unit-testable in isolation. Do NOT add any new npm
    dependency — reuse the existing axios error shape.

    Wire `frontend/app/shop/page.tsx`: introduce a `rateLimited` boolean state and a retry-attempt
    counter (ref). In `fetchShops` catch, branch on `isRateLimitError(err)`:
      - 429 → `setRateLimited(true)` and, while attempts < `MAX_RETRY_ATTEMPTS`, schedule a re-fetch via
        `setTimeout(getRetryDelayMs(err, attempt))`, incrementing the attempt counter; store the timer id
        and clear it on unmount (useEffect cleanup) to avoid leaks / act warnings.
      - non-429 → preserve the existing behaviour (`setShops([])`) AND `setRateLimited(false)`.
    On any successful fetch, reset `rateLimited` to false and the attempt counter to 0. In the render,
    add a NEW branch that takes precedence over the `shops.length === 0` empty state: when `rateLimited`
    is true (and not in the initial skeleton), render a distinguishable busy block — a spinner/pulse plus
    a heading like "High demand right now" and body copy containing the word "retrying" (e.g. "The
    marketplace is busy — retrying automatically…"). When attempts are exhausted, keep the busy state but
    swap the auto-retry line for a manual "Try again" button that re-invokes the fetch — still NEVER the
    authoritative "No shops found" empty state. Render only static copy in this block — never
    `err.message` or the raw response body (the backend already returns a tenant-free 429 body; do not
    surface error internals).

    Wire `frontend/app/shop/[slug]/page.tsx` the same way: add a `rateLimited` state; in the `load()`
    outer catch branch on `isRateLimitError(err)` → busy/retry (bounded, backoff, honour Retry-After)
    instead of `setShop(null)`; non-429 keeps `setShop(null)`. The 4 optional calls (reviews / config /
    promotions / announcements) already `.catch()` to defaults — leave them; only the critical shop +
    products rejections should drive the busy state. Add a busy render branch placed BEFORE the existing
    `if (!shop)` "Shop not found" block so a 429 can never fall through to it. Use the same copy
    convention (contains "retrying").

    Add the tests exactly as specified in <behavior> across the two new test files. Match assertions on
    /retrying/i (case-insensitive substring) so wording tweaks stay green, and assert absence of the
    exact empty-state strings ("No shops found" / "Shop not found").
  </action>
  <verify>
    <automated>cd frontend && npx jest lib/__tests__/public-fetch-retry.test.ts __tests__/shop/rate-limit.test.tsx</automated>
    <automated>cd frontend && npm run build</automated>
  </verify>
  <done>Both new test files pass; `npm run build` (tsc typecheck) is clean; a 429 renders a "retrying" busy state on both /shop and /shop/[slug] while a real empty 200 still renders the empty state.</done>
</task>

<task type="auto">
  <name>Task 2: F-DOCS-1 — repair dead credential recipes, point at env vars, add lockout recovery</name>
  <files>README.md, docs/config/CREDENTIALS.md, docs/guides/TESTING.md, docs/guides/QA_TEST_PLAN.md, docs/setup/SETUP.md, docs/guides/QUICK_START.md</files>
  <action>
    Eliminate every hard-coded `password123` occurrence across these six files and replace the dead
    grant recipes. Confirm coverage first with a grep of `password123` across all six files — it must
    return ZERO after the edits.

    1. Login / credential-table references (README.md ~line 58; CREDENTIALS.md test-user section lines
       ~52 and ~58; TESTING.md ~lines 352/369/381/382; QA_TEST_PLAN.md table ~lines 299/300 and ~339/871;
       SETUP.md ~line 18; QUICK_START.md ~lines 27/28/127): replace the literal `password123` with a
       reference to the env var — e.g. "tenant-a-user / (value of `KC_SEED_USER_PASSWORD` from your
       `.env`)". Do NOT print any real password value.

    2. Fix the structurally-DEAD `test-client` ROPC recipe in CREDENTIALS.md (the "Getting JWT Tokens"
       block, ~lines 99-126): `client_id=test-client` mints a token with `aud:null` that Core rejects
       (401). Replace it with the working confidential-client recipe using `grant_type=password`,
       `client_id=core-api`, `client_secret=$KEYCLOAK_CLIENT_SECRET`, `username=tenant-a-user`,
       `password=$KC_SEED_USER_PASSWORD` against
       `$KC/realms/jtoye-dev/protocol/openid-connect/token`. Also drop the stale hard-coded
       `core-api-secret-2026` literal (CREDENTIALS.md ~line 76) in favour of a `$KEYCLOAK_CLIENT_SECRET`
       reference. Note in prose that `core-api` is a CONFIDENTIAL client so `client_secret` is mandatory.

    3. Fix the `core-api` ROPC recipe in QA_TEST_PLAN.md (~lines 312-333) and every password-grant curl
       in TESTING.md (~lines 90/266/281/310/389/466/573): each is missing `client_secret` (→
       `unauthorized_client`). Add `client_secret=$KEYCLOAK_CLIENT_SECRET` and swap the literal password
       for `password=$KC_SEED_USER_PASSWORD`. Remove or replace any `test-client` remnant — do not leave
       a recipe that produces an aud:null token.

    4. Add a short "Seed-user lockout recovery" note (CREDENTIALS.md security-notes section is the
       canonical home; cross-reference from TESTING.md / QA_TEST_PLAN.md): the realm has
       `bruteForceProtected=true` (issue #87), so replaying a stale/wrong password temporarily LOCKS the
       user. Recovery: Keycloak admin console → realm `jtoye-dev` → Users → tenant-a-user → unlock (or
       wait out the lockout window); always source the password from `KC_SEED_USER_PASSWORD` to avoid
       re-locking. Note that the live tenant-a-user may already be locked from the QA run and needs this
       one-time admin unlock (an operator action, not a code change) — surface it in the SUMMARY too.

    5. Secret-scanner safety: introduce NO real secret values — only env-var NAMES. After editing, run
       the repo secret-scan guard if available (`gitleaks detect --no-banner` or the project pre-commit
       hook) and confirm no new finding; watch for GitGuardian password-shaped-prose false positives and
       phrase recovery notes to reference the env var, never a value.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && ! grep -rn "password123" README.md docs/config/CREDENTIALS.md docs/guides/TESTING.md docs/guides/QA_TEST_PLAN.md docs/setup/SETUP.md docs/guides/QUICK_START.md</automated>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && grep -l "KC_SEED_USER_PASSWORD" docs/config/CREDENTIALS.md docs/guides/TESTING.md docs/guides/QA_TEST_PLAN.md && grep -l "KEYCLOAK_CLIENT_SECRET" docs/config/CREDENTIALS.md docs/guides/QA_TEST_PLAN.md</automated>
  </verify>
  <done>Zero `password123` occurrences remain in the six files; the CREDENTIALS.md token recipe uses `core-api` + `client_secret`; the QA_TEST_PLAN + TESTING.md ROPC recipes include `client_secret`; a lockout-recovery note referencing `KC_SEED_USER_PASSWORD` exists; no new secret-scanner finding.</done>
</task>

<task type="auto">
  <name>Task 3: Reconcile docs/metrics.json + CLAUDE.md testing-standard for the new Jest tests</name>
  <files>docs/metrics.json, CLAUDE.md</files>
  <action>
    Task 1 adds two new Jest test files and several it/test blocks, so the docs-freshness manifest and
    the CLAUDE.md prose counts drift. Reconcile them:

    1. Regenerate the manifest from source reality: run `scripts/docs-freshness.sh --write` from the repo
       root. Do NOT hand-edit `docs/metrics.json` — let the script compute `jest_blocks`, `jest_files`,
       and `total_logical_invocations`. Then run `scripts/docs-freshness.sh` (check mode) and confirm it
       prints "docs-freshness OK".

    2. Sync the CLAUDE.md testing-standard prose (the "project standard is 1243 logical invocations …"
       sentence and its Jest breakdown "234 Jest it/test blocks across 33 files"): update the total, the
       Jest block count, and the Jest file count (33 → 35, since two new test files are added) to the new
       values emitted by the manifest. Grep CLAUDE.md for `1243` and `234` to catch every citation; keep
       the wording/structure identical, only the numbers change. (CLAUDE.md is the only doc that cites
       these totals — no README/PROJECT.md edit needed.)
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && scripts/docs-freshness.sh</automated>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026 && ! grep -q "1243 logical" CLAUDE.md</automated>
  </verify>
  <done>`scripts/docs-freshness.sh` (check mode) passes; docs/metrics.json matches source; CLAUDE.md's total + Jest block/file counts reflect the new tests (the stale "1243"/"234"/"33 files" citations are updated).</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| browser → public Core API | Untrusted client fetches `/public/*`; the API enforces an IP-keyed rate limit (#88) and returns 429 + Retry-After under load |
| repo docs → developers/operators | Credential recipes are executed verbatim by humans; a leaked secret in prose is an exposure |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-svx-01 | Denial of Service | shop pages 429 retry loop | mitigate | Bounded retry (`MAX_RETRY_ATTEMPTS = 4`) + exponential backoff capped at `MAX_DELAY_MS` + honour server `Retry-After`; no unbounded/tight retry loop that would amplify the flood the limiter is defending against |
| T-svx-02 | Information disclosure | busy-state UI render | mitigate | Busy block renders only static copy; never render `error.message` or the raw response body (backend 429 body is already tenant-free — do not surface internals) |
| T-svx-03 | Tampering (supply chain) | frontend deps | accept | No new npm/pip/cargo package is installed — the fix reuses the existing axios error shape; no legitimacy gate required |
| T-svx-04 | Information disclosure | credential docs | mitigate | Recipes reference env-var NAMES (`KC_SEED_USER_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`) only; zero real secret values committed; run gitleaks/pre-commit guard and watch for GitGuardian prose false positives |
</threat_model>

<verification>
- `cd frontend && npx jest lib/__tests__/public-fetch-retry.test.ts __tests__/shop/rate-limit.test.tsx` — new tests green
- `cd frontend && npm run build` — tsc typecheck clean (jest alone does not type-check; this is the required typecheck gate)
- `grep -rn "password123" README.md docs/config/CREDENTIALS.md docs/guides/TESTING.md docs/guides/QA_TEST_PLAN.md docs/setup/SETUP.md docs/guides/QUICK_START.md` — returns nothing
- `scripts/docs-freshness.sh` — prints "docs-freshness OK"
- (Optional live sanity, if the stack is up) a real 429 from `/public/shops` renders the "retrying" busy state, not "No shops found"
</verification>

<success_criteria>
- F-RATE: an HTTP 429 on either storefront surface renders a distinguishable transient "busy / retrying"
  state that auto-retries with backoff; it NEVER renders the authoritative "No shops found" /
  "Shop not found" empty state. A genuine empty 200 still renders the real empty state.
- F-DOCS-1: no doc recipe hard-codes `password123`; the token recipes use `core-api` + `client_secret`
  and reference `KC_SEED_USER_PASSWORD` / `KEYCLOAK_CLIENT_SECRET`; a lockout-recovery note exists.
- docs-freshness gate is green (docs/metrics.json reconciled) and CLAUDE.md's testing-standard prose
  reflects the new Jest test totals.
- Deferred (recorded in SUMMARY, not implemented here): raising / bucketing the backend public per-IP
  limiter (#88); one-time Keycloak admin unlock of the live tenant-a-user if still locked from the QA run.
</success_criteria>

<output>
Create `.planning/quick/260713-svx-fix-qa-high-findings-f-rate-storefront-4/260713-svx-SUMMARY.md` when done.
</output>
