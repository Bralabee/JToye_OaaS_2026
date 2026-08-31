---
task: quick-260831-gnm-fix-p0-p1-customer-surface-audit-finding
verified: 2026-08-31T12:00:24Z
status: human_needed
score: 8/8 must-haves verified at code/test level
overrides_applied: 0
human_verification:
  - test: "Rebuild ALL Compose frontend image (docker compose start does not rebuild), then confirm parity by content/identity before any probe below."
    expected: "Running frontend container's image tag LastTagTime is newer than the newest commit touching frontend build paths; container is actually running the new bundle."
    why_human: "Requires the live stack; this verifier was explicitly instructed not to touch docker."
  - test: "R-01 (P0) cookie-jar probe: sign a vendor out via the dashboard, inspect the browser cookie jar for the six Keycloak SSO cookies, then click 'Sign in with Keycloak' again."
    expected: "Zero Keycloak SSO cookies remain after sign-out; the next Keycloak login is CHALLENGED for credentials, not silently re-entered."
    why_human: "Unit tests only assert the URL vendor-logout composes and that signOut()/navigation are called — jsdom cannot navigate or hold real IdP cookies. This is the single most important truth in the plan and is unverifiable without a running Keycloak + browser."
  - test: "R-03: load '/' on a throttled profile (4x CPU / Slow 4G) and observe the hero entrance."
    expected: "The h1 and persona CTAs stay visible throughout the load — no retroactive blanking. data-entrance=\"skipped\" is present on the root when hydration is late."
    why_human: "entranceIsSafe() is a pure predicate proven correct by unit test; whether the real GSAP bundle actually respects it end-to-end, under real network throttling, is a rendering/timing question a screenshot or unit test cannot answer (a screenshot also cannot verify motion)."
  - test: "R-07 elementFromPoint sweep: over the vendor sidebar Sign Out button (desktop), over the cookie notice's 'Got it' at 390x844 with a non-empty basket, over 'Browse all kitchens' on a zero-result /shop, and over the landing CTA."
    expected: "Each point returns the intended interactive control, never the cookie notice. A real tap on 'Got it' writes the acknowledgement (localStorage/consent state updates)."
    why_human: "The pointer-events split and published bottom-chrome offset are unit-tested structurally (class presence, custom-property publish/clear), but real overlap/occlusion in the rendered DOM — the actual defect the audit found — requires a browser layout engine. The plan itself notes a Playwright click() can pass where a human tap fails, which is exactly why the existing suites missed this originally."
  - test: "R-07 symptom (5): view the cookie notice on a 390px-wide viewport."
    expected: "The compacted copy does not truncate or overflow the card."
    why_human: "Visual/layout check, not expressible as a unit assertion."
  - test: "R-02/R-09 real-browser confirmation: on /shop, clear the search box via typing-backspace, the X button, and 'Browse all kitchens'; separately, type fast enough to produce overlapping in-flight requests."
    expected: "The box stays empty past ~400ms on all three affordances (SSR seed never reappears); the result count always matches the visible grid even when an older request settles after a newer one."
    why_human: "jsdom + fake timers give strong evidence the mechanism is correct (independently spot-checked below), but real network timing/race behavior in a browser is the class of defect the audit found and is explicitly flagged by the executor as owed to the orchestrator."
---

# Quick Task: Fix P0/P1 Customer-Surface Audit Findings — Verification Report

**Task Goal:** Fix P0/P1 customer-surface audit findings: R-01 vendor federated logout, R-02/R-09
search input revert + fetch race, R-03 hero paint-then-vanish, R-04 fail-safe customer signout
teardown, R-07 cookie-notice overlay.

**Verified:** 2026-08-31T12:00:24Z
**Status:** human_needed
**Branch:** `feature/customer-surface-fixes` (5 commits: `b3f8284d`, `87597bee`, `fc80ad60`,
`94213147`, `892799e4`)

## Goal Achievement

Every truth below is achieved at the code level — the mechanism exists, is wired, and is proven
by a Jest arm that was independently re-run for this verification. What remains outstanding is
the browser-level confirmation the plan itself explicitly scopes to "the orchestrator" (a rebuilt
Compose stack + real DOM/network behavior) — the SUMMARY does not claim these either, and this
verifier was instructed not to touch docker. None of the code-level checks failed.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | A vendor who clicks Sign Out and then clicks "Sign in with Keycloak" is CHALLENGED for credentials | ✓ VERIFIED (code) / human_needed (runtime) | `vendorLogout()` fetches the end-session URL while the session exists, calls `signOut({redirect:false})`, then navigates to the Keycloak `end_session` URL — all in a `finally`. Wired into both `sidebar.tsx:143` and `mobile-tab-bar.tsx:177`. Real SSO-termination requires a live Keycloak + browser (listed as human-verify). |
| 2 | The browser-facing vendor end_session URL names the PUBLIC issuer host, never the container-internal one | ✓ VERIFIED | `frontend/app/api/vendor-auth/logout-url/route.ts:55-61` reads `NEXT_PUBLIC_KEYCLOAK_URL \|\| KEYCLOAK_ISSUER`, never `KEYCLOAK_ISSUER_INTERNAL`. Independently re-broken (sourced from `KEYCLOAK_ISSUER_INTERNAL`) and confirmed it reds 4/13 tests naming `keycloak:8080`; restored by `git checkout`, re-ran green 13/13. See "Independent Spot-Checks" below. |
| 3 | Clearing the /shop search box leaves it cleared — the SSR seed never reappears ~400ms later | ✓ VERIFIED | `shop-discovery-client.tsx:226-232,407-429` — `clientOwnsUrl` ref set immediately before `history.replaceState`; URL→state effect reads it to distinguish "seed" from "customer cleared". `npx jest app/shop/__tests__/shop-discovery-client.test.tsx` passes. Real-browser confirmation deferred (human-verify). |
| 4 | A slow keystroke response can never overwrite a newer result set or its count | ✓ VERIFIED | `shop-discovery-client.tsx:290-366` — monotonic `fetchGeneration` ref checked in the success block (line 304), the catch block (line 325), and the finally block (line 364). |
| 5 | A customer sign-out whose server round-trip never settles still clears the marker, the identity and every stored basket's items, and still navigates away | ✓ VERIFIED | `customer-auth.ts:155-176` (`fetchWithTimeout`, AbortController + plain-timer race) and `:461-494` (`customerLogout` — teardown + navigation moved into `finally`). `customer-auth-signout-clears-carts.test.ts`'s "NEVER SETTLES" arm passes using `jest.useFakeTimers()` + `advanceTimersByTimeAsync`, asserting on basket **items** (not key presence). |
| 6 | All three customer sign-out affordances show a busy/disabled state while a sign-out is in flight | ✓ VERIFIED | `storefront-nav.tsx:30-38,170-172`, `public-header.tsx:66-70,176-178,266-268` — each has local `signingOut` state, `disabled={signingOut}`, `aria-busy={signingOut}`, cleared in a `finally`. |
| 7 | A GSAP bundle that hydrates late never hides already-painted landing content; no-JS and reduced-motion paths unchanged | ✓ VERIFIED (predicate) / human_needed (rendering) | `gsap-gate.ts:73-75` (`entranceIsSafe`), `hero-scene.tsx:71-98,108-119` — only the two entrance blocks (headline, persona doors) are gated on `animateEntrance`; heat-wash, how-title, step-rail, steps are untouched scroll-triggered animations, exactly as the plan requires. `splitWords` runs unconditionally. Throttled-profile confirmation deferred (human-verify). |
| 8 | The cookie notice never intercepts a click on interactive chrome; "Got it" is always clickable | ✓ VERIFIED (structural) / human_needed (rendered occlusion) | `cookie-notice.tsx:106,111,161` — wrapper `pointer-events-none` + card `pointer-events-auto`, bottom offset `var(--jt-bottom-chrome, 0px)`. `use-bottom-chrome-height.ts` publishes/clears the property with no dependency array (verified NOT to use `[]`, which the plan calls out as the silent-bug shape). Real `elementFromPoint` occlusion checks deferred (human-verify). |

**Score:** 8/8 truths verified at the code/mechanism level. 6 items require a running stack/browser
to close the loop end-to-end (see Human Verification Required).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `frontend/app/api/vendor-auth/logout-url/route.ts` | Vendor Keycloak end-session URL, server-side | ✓ VERIFIED | 115 lines. Exports `GET`. Reads `session.idToken`, public-host-only base, sanitized redirect, omits `post_logout_redirect_uri` when origin untrustworthy. |
| `frontend/lib/vendor-logout.ts` | `vendorLogout()` + `VENDOR_LOGOUT_TIMEOUT_MS` | ✓ VERIFIED | 86 lines. Both exports present; imports `fetchWithTimeout` from `customer-auth.ts` (single implementation, as required). |
| `frontend/hooks/use-bottom-chrome-height.ts` | Publishes `--jt-bottom-chrome` | ✓ VERIFIED | 90 lines. Exports `useBottomChromeHeight`, `BOTTOM_CHROME_VAR`. No dependency array (confirmed by direct read, not just comment). |
| `frontend/lib/gsap-gate.ts` | `entranceIsSafe()` late-hydration predicate | ✓ VERIFIED | 132 lines. Pure, no `"use client"`, no gsap import. `ENTRANCE_BUDGET_MS=1200`, inclusive boundary (`<=`). |
| `frontend/lib/__tests__/vendor-logout.test.ts` | Vendor sign-out proof | ✓ VERIFIED | 116 lines, passes (part of the 91-test run below). |
| `frontend/hooks/__tests__/use-bottom-chrome-height.test.tsx` | Publish/clear proof | ✓ VERIFIED | 145 lines, passes; sequence-based harness (not mount/unmount-only) as the plan mandates. |
| All 25 `files_modified` (incl. `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`) | — | ✓ VERIFIED | `git diff --name-only 24e82bfa..HEAD` returns exactly the 25 declared paths and nothing else — independently re-run, diff against the plan's declared list is empty. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `sidebar.tsx` | `vendor-logout.ts` | `onClick={() => vendorLogout()}` | ✓ WIRED | `rg -F --count-matches "vendorLogout("` → 1 (independently re-run) |
| `mobile-tab-bar.tsx` | `vendor-logout.ts` | `onClick={() => vendorLogout()}` | ✓ WIRED | count → 1 |
| `vendor-logout.ts` | `logout-url/route.ts` | `fetch('/api/vendor-auth/logout-url?...')` | ✓ WIRED | count → 1 |
| `cookie-notice.tsx` | `use-bottom-chrome-height.ts` | `var(--jt-bottom-chrome)` | ✓ WIRED | count → 2 (import + inline style) |
| `hero-scene.tsx` | `gsap-gate.ts` | `entranceIsSafe(performance.now())` | ✓ WIRED | count → 2 |

All five counts match the SUMMARY's self-check claim (n=1,1,1,2,2) exactly, re-derived independently rather than trusted from the SUMMARY.

### Behavioral Spot-Checks / Test Runs (independently executed by this verifier)

| Check | Command | Result | Status |
|-------|---------|--------|--------|
| Targeted suites (7 files touching all 6 findings) | `npx jest lib/__tests__/vendor-logout.test.ts lib/__tests__/customer-auth-signout-clears-carts.test.ts app/api/vendor-auth/__tests__/logout-url.test.ts app/shop/__tests__/shop-discovery-client.test.tsx lib/__tests__/gsap-gate.test.ts hooks/__tests__/use-bottom-chrome-height.test.tsx components/public/__tests__/cookie-notice.test.tsx --ci --watchAll=false` | 7 suites / 91 tests passed | ✓ PASS |
| Full Jest suite | `npx jest --ci --watchAll=false` | 144 suites / 1544 tests passed | ✓ PASS (matches SUMMARY claim exactly) |
| ESLint | `npm run lint` | 0 errors, 30 warnings | ✓ PASS (matches SUMMARY claim exactly) |
| Type check / build | `rm -rf .next && npm run build` | Build succeeded; `.next/server/app/api/vendor-auth/logout-url/route.js` present | ✓ PASS |
| docs-freshness | `./scripts/docs-freshness.sh` | `OK: metrics match source (3533)` rc=0 | ✓ PASS |
| doc metrics | `./scripts/check-doc-metrics.sh` | `PASS: all 37 prose metric claim(s)` rc=0 | ✓ PASS |
| branch vs base | `./scripts/check-branch-behind-base.sh` | 6 ahead, 0 behind `origin/main` | ✓ PASS |
| working tree | `git status --porcelain` | Only the untracked SUMMARY.md (expected — not yet committed at verify time) | ✓ CLEAN |

### Independent Spot-Checks (fail-direction re-run by the verifier, not trusted from SUMMARY)

Per proof-standards, at least one high-stakes claim was independently re-broken and re-restored
rather than trusted from the executor's own report, given R-01 is the P0.

**R-01 split-horizon arm** — sourced `keycloakBase()` from `KEYCLOAK_ISSUER_INTERNAL` instead of
`NEXT_PUBLIC_KEYCLOAK_URL || KEYCLOAK_ISSUER`:
```
Tests:       4 failed, 9 passed, 13 total
Expected: not "keycloak:8080"  /  Received: "http://keycloak:8080/realms/jtoye-dev/..."
```
Restored via `git checkout -- frontend/app/api/vendor-auth/logout-url/route.ts`; verified by
content (`grep keycloakBase` shows the real implementation restored) and `git status --porcelain`
clean; re-ran green, 13/13. This matches the SUMMARY's own recorded fail-direction output exactly,
independently reproduced.

### Requirements Coverage

| Requirement | Description | Status | Evidence |
|-------------|-------------|--------|----------|
| R-01 (P0) | Vendor federated logout | ✓ SATISFIED (code) / human_needed (SSO termination) | Route + `vendorLogout` + both call sites, all verified above. |
| R-02 | /shop search seed resurrection | ✓ SATISFIED | `clientOwnsUrl` mechanism, unit-proven. |
| R-03 | Hero paint-then-vanish | ✓ SATISFIED (code) / human_needed (throttled rendering) | `entranceIsSafe` + guarded entrance blocks. |
| R-04 | Fail-safe customer signout teardown | ✓ SATISFIED | `fetchWithTimeout` + `finally`-based teardown, unit-proven with fake timers. |
| R-07 | Cookie-notice overlay | ✓ SATISFIED (code) / human_needed (rendered occlusion) | pointer-events split + published offset. |
| R-09 | Stale fetch race | ✓ SATISFIED | Monotonic generation ref across all three settle paths. |

No orphaned requirements — all six IDs in the plan's `requirements:` frontmatter are addressed.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `shop-discovery-client.tsx` | 505 | `placeholder="Try..."` | none | False positive — legitimate HTML input `placeholder` attribute, not a stub marker. |
| `shop-detail-client.tsx` | 295 | "placeholder" in a comment | none | False positive — comment about avoiding layout shift, not a stub marker. |

No `TODO`/`FIXME`/`XXX`/`TBD`/`HACK` markers found in any of the 21 code files in the change-set
(re-run independently; matches the SUMMARY's "Known stubs: None" claim). No debt-marker blocker.

### Human Verification Required

See YAML frontmatter `human_verification` for the structured list. Six items, all explicitly
scoped by the plan itself to "the orchestrator" (post-execution, rebuilt-stack pass) and all
already flagged as owed by the executor's own SUMMARY — none are gaps in the delivered code.

1. **Rebuild + parity** — Compose frontend image must be rebuilt (not just restarted) and proven
   current before any of the following probes are meaningful.
2. **R-01 cookie-jar probe (P0)** — the single most important unproven claim: real Keycloak SSO
   termination on sign-out.
3. **R-03 throttled-profile hero capture** — real-bundle timing behavior under 4x CPU / Slow 4G.
4. **R-07 elementFromPoint sweep + real tap** — actual DOM occlusion across four surfaces.
5. **R-07 copy truncation at 390px** — visual check.
6. **R-02/R-09 real-browser search behavior** — actual network race timing.

### Gaps Summary

None. Every must-have truth is implemented, wired, and covered by a passing (and in the P0 case,
independently re-broken and re-verified) automated test. The remaining items are browser/runtime
truths the plan's own `<verify>` blocks explicitly assign to a later, stack-dependent pass — they
were never claimable from Jest and the SUMMARY does not claim them. Status is `human_needed`
rather than `passed` per the decision rule: any non-empty human-verification list takes priority
over an all-green automated score.

---

_Verified: 2026-08-31T12:00:24Z_
_Verifier: Claude (gsd-verifier)_
