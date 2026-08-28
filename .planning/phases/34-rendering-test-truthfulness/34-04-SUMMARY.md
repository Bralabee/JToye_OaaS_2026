---
phase: 34-rendering-test-truthfulness
plan: 04
subsystem: ui
tags: [react, nextjs, oauth, eslint, playwright, jest, ssr]

# Dependency graph
requires:
  - phase: 19-full-frontend-overhaul
    provides: the storefront shell and the /shop landing the callback's error path returns a shopper to
  - phase: 33-consumer-product
    provides: "/shop as a server component with an <h1> landmark, which Block 1's destination assertion depends on"
provides:
  - "app/shop/auth/callback/page.tsx: the missing-code error derived during render; the third of four #99-follow-up set-state-in-effect suppressions deleted"
  - "A ref-guarded token exchange so a one-time authorization code cannot be redeemed twice (T-34-04-03)"
  - "frontend/e2e/storefront-auth-callback.spec.ts: browser coverage of a route #202's own acceptance list flags as uncovered"
  - "frontend/app/shop/auth/callback/__tests__/callback-page.test.tsx: a renderToStaticMarkup assertion that distinguishes derive-during-render from a mount effect, which no DOM assertion can"
affects: [34-05, 34-06, 34-10, 202, 542]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Server-render assertion as the test for a render-time derivation: renderToStaticMarkup is the one environment where effects provably never run, so a value that appears there was computed during render"
    - "Serving the branch's own build on a dedicated port for E2E, because the compose container is built from main and a spec run against it can neither prove the change nor fail on its break arm"

key-files:
  created:
    - frontend/e2e/storefront-auth-callback.spec.ts
    - frontend/app/shop/auth/callback/__tests__/callback-page.test.tsx
  modified:
    - frontend/app/shop/auth/callback/page.tsx

key-decisions:
  - "Added a Jest suite beyond the plan's two files: the plan's own <behavior> block enumerates behaviours no Playwright block can reach (handleCallback called exactly once; called with the returned state; a resolved profile redirecting), and the one-time-code hazard is a security mitigation that has to be falsifiable"
  - "The load-bearing assertion is renderToStaticMarkup, not the DOM: @testing-library flushes effects inside act, so a mount-effect value and a render-time derivation produce an IDENTICAL DOM. Measured — 4 of 6 blocks passed on the unfixed page"
  - "Block 2 asserts the failure copy directly rather than the plan's weaker spinner-left fallback: handleCallback returns null with no network call when sessionStorage holds no PKCE verifier, so the strong form is available and a permanently spinning page fails it"
  - "Ran the spec against a build of THIS branch on :3117, never the compose :3000 container, whose served callback HTML carries 0 occurrences of the error copy"
  - "docs/metrics.json deliberately NOT regenerated — plan 34-10 is its single writer. Measured drift recorded below so 34-10 has the numbers"

patterns-established:
  - "Prove a break is live in the served bytes BEFORE running the break arm — a rebuilt-but-not-restarted server makes an arm measure the old artifact"
  - "Both error strings as module constants, so a reflected-input sink cannot be reintroduced by an edit that looks innocuous"

requirements-completed: [TRUTH-01]

# Metrics
duration: 68min
completed: 2026-08-28
---

# Phase 34 Plan 04: OAuth callback missing-code error derived during render — Summary

**The code-less OAuth callback now SERVES its explanation and its way out instead of a spinner that only resolves after hydration; the third `#99 follow-up` suppression is deleted, a one-time authorization code can no longer be exchanged twice, and the route has browser coverage it did not have.**

## Performance

- **Duration:** ~68 min
- **Started:** 2026-08-28T20:32Z (approx, first measurement)
- **Completed:** 2026-08-28T21:40:36Z
- **Tasks:** 2 (Task 1 TDD: RED → GREEN)
- **Files modified:** 3 (2 created, 1 modified)

## Accomplishments

- **The change was never only a lint concession.** `app/layout.tsx:18` sets `dynamic = "force-dynamic"`, so this page is server-rendered on every request and an effect cannot run there. Measured on the served bytes, both directions: the error copy appears **1×** on this branch and **0×** on `main`'s running container, where `Signing you in` appears instead.
- **A real security defect fixed, not anticipated by the plan's task ordering but named by its threat register.** Under StrictMode the effect ran twice and `handleCallback` was called **2×** on a single-use authorization code (T-34-04-03). Now 1×, guarded by a ref.
- **The ESLint gate was shown to fire on a reintroduction** — ROADMAP criterion 2's actual requirement — with the restore verified by blob identity and a closing clean arm.
- **Two Playwright break arms executed**, including one proving the block is not satisfiable by any other page.

## Task Commits

1. **Task 1 (RED): failing coverage for the render-time error branch** — `b4a06774` (test)
2. **Task 1 (GREEN): derive the missing-code error during render** — `a6cb10c0` (fix)
3. **Task 2: cover the callback error path as a landing destination** — `0f6dc8a1` (test)
4. **Rule 1 auto-fix: a real `CustomerProfile` in the test, not a cast** — `72bec78b` (fix)

## Files Created/Modified

- `frontend/app/shop/auth/callback/page.tsx` — `const code = searchParams.get("code")` read during render; `NO_CODE` / `AUTH_FAILED` as module constants; the exchange kept in its effect behind a ref guard; the `#99 follow-up` suppression deleted.
- `frontend/app/shop/auth/callback/__tests__/callback-page.test.tsx` — 6 blocks. The first uses `renderToStaticMarkup` because no DOM assertion can tell the two shapes apart.
- `frontend/e2e/storefront-auth-callback.spec.ts` — 2 blocks, both viewports, relative navigation, LIVE-subtree scoping.

## Evidence — every assertion in both directions

### ESLint (ROADMAP criterion 2)

| Direction | Command | Result |
|---|---|---|
| CLEAN (pre-arm) | `npx eslint app/shop/auth/callback/page.tsx` | **rc=0**, 0 problems |
| BREAK ARM | reintroduced `if (!code) { setError(NO_CODE); return }` in the effect body, no suppression | **rc=1** — `50:18 error … react-hooks/set-state-in-effect`, "Avoid calling setState() directly within an effect" |
| RESTORE | `git checkout --` then `git hash-object` | `d321d9330a0746b384b2ac9205f01aa2081b3f74` == `git rev-parse HEAD:…` |
| CLEAN (closing) | `npx eslint app/shop/auth/callback/page.tsx` | **rc=0** |

### The suppression grep, with its positive control

| Command | Result |
|---|---|
| `rg -uu -c 'refactor tracked in issue #99 follow-up' frontend/app/shop/auth/callback/page.tsx` | **0** (rc=1). Pre-edit control: **1** (rc=0) |
| Positive control, same pattern, other three sites | `use-customer-session.ts:1`, `sidebar.tsx:1`, `mobile-tab-bar.tsx:1` — rc=0 |

The positive control is load-bearing: without it, a 0 is evidence about the pattern or the scope, not about the file. The tree-wide count went **4 → 3**.

### Jest — RED then GREEN, on the same six blocks

| Run | Result |
|---|---|
| RED (unfixed page) | **2 failed, 4 passed, 6 total** |
| GREEN (fixed page) | **6 passed, 6 total** |
| Full frontend suite | **121 suites / 1236 tests / 0 failures** |

The two RED failures were the defect, not the harness:
- `renderToStaticMarkup` carried the error copy **0×** — "expected true, received false".
- StrictMode: "Expected number of calls: 1 / Received number of calls: **2**".

**That 4-of-6 pass rate on the unfixed page is the finding worth keeping.** Four blocks — including "renders the error copy and a `/shop` link with no code" — were green *before* any change, because `render()` flushes effects inside `act`. A DOM-only suite would have reported this plan's work as already done.

### Served bytes — the runtime-parity contrast

Measured with `curl` + `grep -o … | wc -l` on both runtimes for `/shop/auth/callback`:

| Token | `:3000` (compose, built from `main`) | `:3117` (this branch's build) |
|---|---|---|
| `No authorization code received.` | **0** | **1** |
| `Back to shop` | **0** | **1** |
| `Signing you in` | **1** | **0** |
| `animate-spin` | 2 | 1 (the Suspense fallback only) |

This is why the spec was NOT run against `:3000`: on that container Block 1's served-HTML assertion fails and its DOM assertions pass for reasons unrelated to the change.

### Playwright — clean → arms → clean

| Run | Project | Result |
|---|---|---|
| CLEAN | desktop | **2 passed** (1.7s) |
| CLEAN | mobile | **2 passed** (1.3s) |
| BREAK ARM 1 — `if (message)` render branch deleted, rebuilt, server restarted, break confirmed live in the served HTML (0 occurrences) first | desktop | **2 failed** — Block 1: *"the error copy appeared 0 times in the served HTML before 34-04"*; Block 2: `getByText('Authentication failed. Please try again.')` not found |
| BREAK ARM 2 — Block 1 repointed at `/shop/auth/definitely-not-the-callback` | desktop | **1 failed, 1 passed** — Block 1 `Expected: 200 / Received: 404`; Block 2, untouched, stayed green, which proves the arm isolated the block it targeted |
| CLEAN (closing) | desktop | **2 passed** |
| CLEAN (closing) | mobile | **2 passed** |

Both restores verified by blob identity, not by `git diff --stat`:
`page.tsx` → `d321d9330a0746b384b2ac9205f01aa2081b3f74`; `storefront-auth-callback.spec.ts` → `9605c5c50eb95af0b3e6f36395132641b1f478c7`. Both equal `git rev-parse HEAD:<path>`. Closing `git status --short` empty.

The break-arm harness kills the **process group** (`setsid` + `kill -- -PID`) and then proves `:3117` refuses (`curl http_code=000`) before restarting — plan 31-17's first harness left `next-server` alive and printed three PASS lines having measured nothing.

### Gates

| Gate | Result |
|---|---|
| `npx eslint .` | **rc=0** — verdict line `✖ 34 problems (0 errors, 34 warnings)`; **0** of them name any file this plan touched (positive control on the same log: 14 `frontend/app` hits) |
| `npm run build` | **rc=0**, `/shop/auth/callback` emitted as `ƒ` (dynamic) |
| `npx tsc --noEmit` | **rc=0** (after the Rule 1 fix below) |
| `scripts/check-e2e-baseurl-contract.sh` | **rc=0** — 23 specs scanned, 0 divergent |
| `scripts/check-e2e-typecheck.sh` | **rc=0** — 26 e2e files clean |

### Threat register

| Threat | Disposition | Evidence |
|---|---|---|
| T-34-04-01 tampering via error copy | mitigated | Both strings are module constants. `rg -uu -n 'setError\(\`\|\$\{' page.tsx` → 0 (rc=1); positive control on `customer-auth.ts` → 4 (rc=0) |
| T-34-04-02 open redirect | accepted, unchanged | `git diff --name-only <base> HEAD -- frontend/lib/customer-auth.ts` → empty. Full change set is exactly the 3 files listed above |
| T-34-04-03 one-time code exchanged twice | mitigated | StrictMode call count 2 → 1, asserted in Jest; the reason is written into the file |
| T-34-04-04 real code in an artifact | accepted | Spec uses `not-a-real-code` only |
| T-34-04-SC npm installs | mitigated | `git diff --name-only <base> HEAD -- frontend/package.json frontend/package-lock.json` → empty |

## Decisions Made

1. **A Jest suite was added beyond the plan's two declared files.** The plan's Task 1 `<behavior>` block specifies four behaviours, three of which no browser block can observe (`handleCallback` called exactly once, called with the returned `state`, a resolved profile redirecting to `getAuthReturnUrl()`) — the exchange short-circuits to null in a browser with no PKCE verifier. Since the one-time-code guard is a threat-register mitigation, leaving it unfalsifiable was not an option. New file, single owner, no conflict surface.
2. **Block 2 asserts the strong form.** The plan permitted a weaker "the page leaves the spinner state" fallback if the exchange endpoint were unreachable. It is reachable-by-short-circuit: `customer-auth.ts:262` returns null the moment `sessionStorage` holds no PKCE verifier — the exact state of a shopper opening a callback URL out of band. The strong assertion was therefore used, and the fallback is explicitly recorded in the spec as not needed.
3. **The `.animate-spin` absence is asserted on the settled DOM, not the served HTML.** The served bytes legitimately contain one `animate-spin` — the Suspense fallback Next streams before the resolved content. Asserting 0 there would fail on a correct tree.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The Jest mock resolved a `CustomerProfile` that cannot exist**

- **Found during:** Task 2 verification (`npx tsc --noEmit`)
- **Issue:** `mockResolvedValue({ id, email, name } as …)` was missing `sub` and `emailVerified`. **All six Jest blocks were green over it** — jest's babel transform strips types without checking them. `tsc --noEmit` returned **rc=2, TS2352**.
- **Fix:** A real `CustomerProfile` literal, with a comment naming the mechanism.
- **Files modified:** `frontend/app/shop/auth/callback/__tests__/callback-page.test.tsx`
- **Verification:** `tsc --noEmit` rc=0; Jest still 6/6.
- **Committed in:** `72bec78b`

**2. [Rule 3 - Blocking] Two jsdom polyfills for `react-dom/server`**

- **Found during:** Task 1 RED
- **Issue:** Under `testEnvironment: jsdom`, `react-dom/server` resolves to its browser build and needs `MessageChannel` and `TextEncoder`; the suite died at import time before any assertion ran. Then `node:worker_threads`' `MessageChannel` re-`ref`s itself when `onmessage` is assigned, so jest hung to the 240s timeout (**rc=124**) and would have needed `--forceExit` — which masks genuinely leaked handles.
- **Fix:** Both polyfills local to the test file (not `jest.setup.js`, which every parallel worktree shares); the channel is a 2-member fake over `setTimeout`, matching the exact surface react-dom uses at `react-dom-server.browser.development.js:8818-8822`.
- **Verification:** jest exits on its own, no "did not exit" warning.
- **Committed in:** `b4a06774`

---

**Total deviations:** 2 auto-fixed (1 bug, 1 blocking). No scope creep — both are inside the files this plan owns.

## Issues Encountered

- **The worktree was created 76-ish commits behind its intended base** (HEAD `896c8828`, expected `0b6a581c`; `merge-base` proved HEAD was a strict ancestor, so the reset was a fast-forward with no commits of its own). The recorded worktree-creation defect, in the recorded shape. Reset at startup per `feedback_worktree_merge.md`.
- **`frontend/node_modules` is absent in a worktree.** `npm ci` rc=0.
- **`docs-freshness.sh` is RED on this branch, by design.** Measured drift, for 34-10 (its single writer): `jest_blocks` 1230 → **1236**, `jest_files` 120 → **121**, `playwright_blocks` 113 → **115**, `playwright_specs` 22 → **23**, `total_logical_invocations` 3188 → **3196**. The +2 Playwright blocks and +6 Jest blocks are exactly this plan's two new files; no other counter moved.

## Known Stubs

None.

## Threat Flags

None — no new network endpoint, auth path, file access pattern or schema change. The route already existed; only the branch it takes on first render changed.

## User Setup Required

None.

## Next Phase Readiness

- **The compose runtime is still built from `main` and does NOT contain this change.** `:3000` serves 0 occurrences of the error copy. Whoever merges this wave must rebuild the frontend image (`up -d --build frontend` — `start` will not do it) and re-verify by content, not by status code. The `:3117` server this plan used has been stopped and the port proven to refuse.
- **One of four `#99 follow-up` sites remains after this plan and its siblings**: `sidebar.tsx:63`, `mobile-tab-bar.tsx:64` and `use-customer-session.ts:35` still carry the marker on this branch. ROADMAP criterion 2 needs all four; this plan owns exactly one.
- `docs/metrics.json` needs 34-10, with the numbers above.

---
*Phase: 34-rendering-test-truthfulness*
*Completed: 2026-08-28*

## Self-Check: PASSED

- All 4 files present on disk (3 code, 1 summary) — verified with `ls -1`, which errors on a missing path.
- All 4 commit hashes present in `git log --oneline --all` (rc=0): `b4a06774`, `a6cb10c0`, `0f6dc8a1`, `72bec78b`.
