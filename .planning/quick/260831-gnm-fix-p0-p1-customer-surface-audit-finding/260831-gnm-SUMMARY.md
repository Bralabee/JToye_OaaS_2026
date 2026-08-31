---
phase: quick-260831-gnm
plan: 01
subsystem: frontend
tags: [security, auth, keycloak, storefront, dashboard, gsap, cookie-notice, r-01, r-02, r-03, r-04, r-07, r-09]
requirements: [R-01, R-02, R-03, R-04, R-07, R-09]
branch: feature/customer-surface-fixes
status: complete
tasks_completed: 3
review_round: complete (2 Critical + 5 of 7 Warnings + 1 Info addressed)
commits: 11
duration_minutes: 143
completed: 2026-08-31

dependency_graph:
  requires:
    - "frontend/auth.ts — already places session.idToken via buildSession; NOT edited"
    - "frontend/lib/public-origin.ts — resolvePublicOrigin, null is a real answer"
    - "frontend/lib/cart-identity.ts — clearStoredCarts / CUSTOMER_ID_KEY"
  provides:
    - "GET /api/vendor-auth/logout-url — vendor Keycloak end-session URL, built server-side"
    - "vendorLogout() — latched, both steps bounded, front-channel navigation"
    - "fetchWithTimeout / LOGOUT_FETCH_TIMEOUT_MS — one bounded fetch for BOTH sign-out paths"
    - "entranceIsSafe() / ENTRANCE_BUDGET_MS — the late-hydration predicate"
    - "entranceIsSafeForMount() — first-mount latch, so soft navs still animate"
    - "useBottomChromeHeight() / BOTTOM_CHROME_VAR — published bottom-chrome offset"
    - "BottomNoticeShell — ONE bottom-chrome contract for notice AND consent banner"
  affects:
    - "vendor dashboard sign-out (sidebar + mobile tab bar)"
    - "customer sign-out (storefront nav + public header desktop and sheet)"
    - "public /shop directory search"
    - "landing hero entrance"
    - "cookie notice on every public and dashboard surface"

tech_stack:
  added: []           # no package installed — threat T-QF-SC had nothing to act on
  patterns:
    - "AbortController-backed Promise.race for a fake-timer-drivable fetch deadline"
    - "monotonic request generation instead of axios cancellation"
    - "publisher-side CSS custom property instead of a consumer-side measured offset"
    - "pointer-events split (none wrapper / auto card) to remove occlusion as a CLASS"

key_files:
  created:
    - frontend/app/api/vendor-auth/logout-url/route.ts
    - frontend/app/api/vendor-auth/__tests__/logout-url.test.ts
    - frontend/lib/vendor-logout.ts
    - frontend/lib/__tests__/vendor-logout.test.ts
    - frontend/hooks/use-bottom-chrome-height.ts
    - frontend/hooks/__tests__/use-bottom-chrome-height.test.tsx
    - frontend/components/public/bottom-notice-shell.tsx            # review round
    - frontend/components/dashboard/__tests__/vendor-signout-affordance.test.tsx  # review round
  modified:
    - frontend/app/shop/shop-discovery-client.tsx
    - frontend/app/shop/__tests__/shop-discovery-client.test.tsx
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/mobile-tab-bar.tsx
    - frontend/components/storefront/storefront-nav.tsx
    - frontend/components/public/public-header.tsx
    - frontend/components/public/cookie-notice.tsx
    - frontend/components/public/__tests__/cookie-notice.test.tsx
    - frontend/components/marketing/hero-scene.tsx
    - frontend/lib/customer-auth.ts
    - frontend/lib/customer-idp-logout.ts
    - frontend/lib/__tests__/customer-auth-signout-clears-carts.test.ts
    - frontend/lib/gsap-gate.ts
    - frontend/lib/__tests__/gsap-gate.test.ts
    - frontend/components/public/consent-banner.tsx                    # review round
    - frontend/components/storefront/__tests__/storefront-nav.test.tsx # review round
    - frontend/app/api/customer-auth/logout-url/route.ts               # review round
    - frontend/app/api/customer-auth/__tests__/logout-url-origin.test.ts # review round
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md

metrics:
  jest_blocks: 1505 -> 1566
  jest_files: 141 -> 145
  total_logical_invocations: 3494 -> 3555
  playwright_blocks: 127        # UNCHANGED, as required
  playwright_specs: 27          # UNCHANGED, as required
  coverage: "68.94 / 60.19 / 64.84 / 70.55 (floor 63/55/60/64)"
  eslint: "0 errors, 30 warnings (baseline 31 — one removed, see commit 892799e4)"
---

# Quick 260831-gnm: P0/P1 Customer-Surface Audit Fixes — Summary

Six findings from the 2026-08-31 five-lane customer-surface audit closed across five
atomic commits: vendor federated logout (R-01, **P0** — a live account takeover on a
shared device), the /shop search seed resurrection and stale-response race (R-02/R-09),
the fail-open customer sign-out teardown (R-04), the retroactive hero blanking (R-03),
and the systemic cookie-notice overlay (R-07).

## Commits

| # | Hash | Findings | Scope |
|---|------|----------|-------|
| 1 | `b3f8284d` | R-02, R-09 | `/shop` search stickiness + request generation |
| 2 | `87597bee` | R-01 (**P0**) | vendor end-session route + `vendorLogout` + both dashboard call sites + `fetchWithTimeout` |
| 3 | `fc80ad60` | R-04 | bounded + unconditional customer teardown, IdP call timeout, busy state on all three affordances |
| 4 | `94213147` | R-03, R-07 | entrance budget, published bottom-chrome offset, pointer-events split, single metrics regeneration |
| 5 | `892799e4` | — | Rule-1 fix: an eslint-disable measured unnecessary (found by inventory diff, not by the total) |

25 files changed, exactly the plan's declared `files_modified` set — verified by
`git diff --name-only 24e82bfa..HEAD`, which returns those 25 and nothing else. No
Keycloak realm config, no `size-adjust` work, no unlisted P2, no package installed.

> **EVERY FIGURE FROM HERE TO THE `---` DIVIDER IS ROUND-1 AS-MEASURED AND IS NOW
> SUPERSEDED.** A code review followed; six further commits landed, and the file
> count, the metrics and the gate results all moved. This section is kept as the
> faithful record of what round 1 measured — it is not the current state. The
> current state is the **Review Round** section at the end. Re-read a number there
> before quoting it anywhere.

---

## Fail directions — both directions' real output

Every gate below was run against a deliberately broken input **and** against the real
tree. Bracketing is clean → arm → clean throughout, with every restore verified BY
CONTENT (a unique `BREAK-ARM-*` token plus `git status --porcelain` on the named path),
never by `git diff --stat`.

### R-02 — the SSR seed resurrecting a cleared query

**Fail (pre-fix component, before any source edit):**
```
✓ CONTROL: an externally-changed ?q= is still adopted into the input (52 ms)
✕ does not resurrect the SSR seed ~400 ms after the box is cleared (54 ms)
✕ the X button and 'Browse all kitchens' clear just as permanently (49 ms)
    Expected: ""
    Received: "jollof"
Tests:       3 failed, 23 passed, 26 total
```

**Second fail arm, against the AS-SHIPPED (restructured) code** — necessary because the
derivation was restructured after the first run (see Deviation 1), so the original
evidence described a different code shape:
```
# nextQuery reverted to `rawUrlQuery ?? initialQuery`
✕ does not resurrect the SSR seed ~400 ms after the box is cleared
✕ the X button and 'Browse all kitchens' clear just as permanently
    Expected: ""  Received: "jollof"     (x2)
✓ CONTROL: an externally-changed ?q= is still adopted into the input
Tests:       2 failed, 24 passed, 26 total   rc=1
```

**Pass:** `Tests: 26 passed, 26 total  rc=0`. Restore verified: `BREAK-ARM-R02` → 0
occurrences (`grep_rc=1`), `clientOwnsUrl.current ? "" : initialQuery` → 1, porcelain empty.

### R-09 — a stale keystroke response overwriting a newer result set

**Fail (pre-fix):**
```
✓ CONTROL: the ordinary single-request path still renders its result (18 ms)
✕ keeps the newer result set and count when an older request settles last (29 ms)
  TestingLibraryElementError: Unable to find an element with the text: Dulwich Near Kitchen
```
The DOM dump confirmed the grid held **Stale Kitchen** — request A's late answer had
replaced request B's.

**Second fail arm, as-shipped code** (success-path generation guard removed):
```
✕ keeps the newer result set and count when an older request settles last (33 ms)
  Unable to find an element with the text: Dulwich Near Kitchen
Tests:       1 failed, 25 passed, 26 total   rc=1
```
**Pass:** 26/26, rc=0. Restore verified by content.

### R-01 (b) — the container-hostname split horizon

Both `KEYCLOAK_ISSUER` (public) and `KEYCLOAK_ISSUER_INTERNAL` are set in the fixture, so
a route reaching for the wrong one still produces a valid-*looking* URL. The arm sourced
the base from the internal issuer:
```
✕ names the PUBLIC issuer host and carries the id_token_hint
✕ SPLIT HORIZON: never emits the container-internal host, even when it is set
✕ prefers NEXT_PUBLIC_KEYCLOAK_URL over KEYCLOAK_ISSUER when both are set
✕ with NO trustworthy origin, keeps the id_token_hint and OMITS post_logout_redirect_uri
    Expected: "localhost:8085"   Received: "keycloak:8080"
    Expected substring: not "keycloak:8080"
    Received string: "http://keycloak:8080/realms/jtoye-dev/protocol/openid-connect/logout?id_token_hint=ID&post_logout_redirect_uri=..."
Tests:       4 failed, 9 passed, 13 total   rc=1
```
**Pass:** 13/13, rc=0. Restore: `BREAK-ARM-B` → 0, porcelain empty.

### R-01 (c) — the open-redirect sanitiser

Arm returned the raw `?redirect=` unsanitised:
```
✕ rejects a protocol-relative redirect  → Received "http://localhost:3000//evil.example"
✕ rejects a backslash trick             → Received "http://localhost:3000/\\evil.example"
✕ rejects a absolute http               → Received "http://localhost:3000http://evil.example/steal"
✕ rejects a absolute https              → Received "http://localhost:3000https://evil.example/steal"
✕ rejects a scheme-ish                  → Received "http://localhost:3000javascript:alert(1)"
✕ rejects a empty                       → Received "http://localhost:3000"
✓ CONTROL: a legitimate relative redirect is still honoured
Tests:       6 failed, 7 passed, 13 total   rc=1
```
The CONTROL staying green is what stops "everything falls back to /auth/signin" from
passing as a working sanitiser. **Pass:** 13/13, rc=0.

### R-04 (a) — the never-settling round trip

The strongest available form: the arm ran against the **genuine pre-fix
`customerLogout`**, not a synthetic break, because the test was written before the
restructure.
```
✓ removes every stored basket alongside the session marker (10 ms)
✓ still clears the baskets when the server round-trip fails (1 ms)
✕ clears the local state even when the round-trip NEVER SETTLES (5002 ms)
✓ CONTROL: the helper can still SEE a basket that was not cleared
  ● thrown: "Exceeded timeout of 5000 ms for a test.
Tests:       1 failed, 5 passed, 6 total   rc=1
```
**The failure is a jest TIMEOUT, not a wrong value** — `customerLogout()` simply never
resolved. Recorded as-is rather than smoothed into an assertion.

**Repeated post-commit against the shipped code** (teardown moved back out of `finally`,
`fetchWithTimeout` reverted to `fetch`): identical shape, `5001 ms`, rc=1.
**Pass:** 6/6, rc=0. Restore verified: `BREAK-ARM-A` → 0, `fetchWithTimeout` → 5 lines.

### R-07 (c) — the pointer-events split

Two arms, one per class, because a class-name assertion seen only passing may be matching
something else entirely.
```
# wrapper: pointer-events-none deleted
✕ cannot intercept a click outside its own card (4 ms)
    Expected pattern: /pointer-events-none/
    Received string:  "fixed inset-x-0 z-40"
Tests: 1 failed, 19 passed, 20 total   rc=1

# card: pointer-events-auto deleted
✕ cannot intercept a click outside its own card (10 ms)
    expect(received).not.toBeNull()
    Received: null
Tests: 1 failed, 19 passed, 20 total   rc=1
```
**Pass:** 20/20 both times after restore; `pointer-events-auto` → 2 occurrences.

### R-07 (d) — the hook cleanup

```
# cleanup no longer clears the property
✕ removes the property on unmount FROM A PUBLISHED STATE (4 ms)
    Expected: ""     Received: "56px"
Tests: 1 failed, 4 passed, 5 total   rc=1
```
Note the *other* unmount step (step 4 of the sequence arm) unmounts from an
already-cleared state and therefore **cannot** see a broken cleanup — it stayed green.
That is why a dedicated "unmount from a PUBLISHED state" arm exists.

### R-07 — the harness itself, proven non-vacuous (arm beyond the plan)

The plan warns that a mount/unmount harness passes against the broken
`useEffect(…, [])` shape. That warning was **executed**, before commit: the hook was
rebuilt with the forbidden empty array.
```
✕ publishes on APPEARANCE, clears on disappearance, and clears on unmount (21 ms)
    Expected: "56px"     Received: ""
✓ removes the property on unmount FROM A PUBLISHED STATE
✓ treats a zero-height (breakpoint-hidden) bar as no bar at all
✓ re-measures on resize, with no re-render to prompt it
✓ CONTROL: the harness's own stub really does report a height
Tests: 1 failed, 4 passed, 5 total   rc=1
```
Exactly the load-bearing arm reds and only that one. **Pass:** 5/5, rc=0.

### R-03 (e) — the entrance predicate

```
# comparison inverted (<= becomes >)
✕ plays the entrance at first paint                       Expected true / Received false
✕ plays the entrance EXACTLY on the budget (inclusive)    Expected true / Received false
✕ REFUSES the entrance one millisecond past the budget    Expected false / Received true
✕ refuses a genuinely late hydration by a wide margin     Expected false / Received true
✕ treats a negative elapsed reading as safe               Expected true / Received false
Tests: 5 failed, 11 passed, 16 total   rc=1

# off-by-one (<= becomes <) — arm beyond the plan, isolating the boundary
✕ plays the entrance EXACTLY on the budget (inclusive)    Expected true / Received false
Tests: 1 failed, 15 passed, 16 total   rc=1
```
The second arm matters: the inverted arm reds five tests and would be satisfied by a
loose boundary. The off-by-one reds exactly one, proving the inclusive boundary is a
measurement rather than an accident. **Pass:** 16/16, rc=0.

### (a) `npm run build` — the only frontend type gate

Jest does not type-check, so the build's green had to be shown capable of red first.
```
# ENTRANCE_BUDGET_MS assigned a string
lib/gsap-gate.ts(74,10): error TS2365: Operator '<=' cannot be applied to types 'number' and 'string'.
Failed to type check.
BUILD rc=1
```
**Pass:** from a cleared `.next`, rc=0. Not a cached no-op — `.next` was `rm -rf`'d before
both runs, and the new route really was emitted:
`.next/server/app/api/vendor-auth/logout-url/route.js` exists.

*(The error is TS2365 rather than the plan's suggested TS2322 — the assignment is
inferred, so the type error surfaces at the comparison rather than the declaration. Same
gate, same "Failed to type check" verdict, rc=1.)*

### (b) `check-doc-metrics.sh`

```
# README "Total: 3533" hand-edited to 3534
FAIL: README.md [total_logical_invocations]: doc says 3534, docs/metrics.json says 3533
FAIL: prose metric claim(s) disagree with docs/metrics.json
rc=1        # rc=1, NOT rc=2 — a real disagreement, not a VOID
```
**Pass:** `PASS: all 37 prose metric claim(s) across 3 doc(s) match docs/metrics.json.`
rc=0. Restore verified by content (`3534` → 0 occurrences, `3533` → 1).

---

## Closing clean state (the arm that proves the restores happened)

| Gate | Command | Result |
|------|---------|--------|
| Full Jest | `npx jest --ci --watchAll=false` | **144 suites / 1544 tests passed**, rc=0 |
| Coverage floor | `npx jest --coverage` | 68.72 / 60.17 / 64.42 / 70.29 vs floor 63/55/60/64 — all clear, all **higher** than the 34-08 baseline |
| ESLint | `npm run lint` | **0 errors**, 30 warnings, rc=0 |
| Type check | `rm -rf .next && npm run build` | rc=0 |
| docs-freshness | `./scripts/docs-freshness.sh` | `OK: metrics match source (3533)`, rc=0 |
| doc metrics | `./scripts/check-doc-metrics.sh` | `PASS: all 37 prose metric claim(s)`, rc=0 |
| branch vs base | `./scripts/check-branch-behind-base.sh` | `6 ahead, 0 behind` origin/main, rc=0 |
| working tree | `git status --porcelain` | empty |

**Read the ESLint VERDICT line, not the last line** — eslint's final line is the fixable
count. The verdict is `0 errors`.

**One VOID encountered and reported, not swallowed.** A closing run of the two doc gates
printed `rc=127` because the shell's cwd was `frontend/` and the scripts live at the repo
root. 127 is "command not found", which is a VOID and neither a pass nor a fail. Re-run
from the root: both rc=0. Recorded because the rc was printed; had it not been, a `tail
-1` of empty output would have read as silence.

---

## Deviations from plan

### 1. [Rule 3 — blocking] R-02's derivation could not be written where the plan put it

**Found during:** Task 1, at the lint step.
**Issue:** The plan said "introduce a ref … and read it where `urlQuery` is derived". That
is a ref read during render, which `react-hooks/refs` forbids — and it is an **error**,
not a warning, under the react-hooks 7.1.1 adopted on this branch's base (`9acfd186`):
```
229:31  error  Error: Cannot access refs during render   react-hooks/refs
✖ 1 problem (1 error, 0 warnings)   rc=1
```
**Fix:** the single derived `urlQuery` was split into its two roles, which is what the
plan's own prose argues for anyway ("the seed is a FIRST-RENDER default, not a standing
fallback"):
- `rawUrlQuery` — what the URL *says* (`null` = "no `q`", distinct from "the query is empty")
- `seededQuery` — the first-render default, seeding state and `appliedUrlQuery`
- the URL→state effect reads `clientOwnsUrl.current` and decides what an absent `q` *means*

`appliedUrlQuery` is untouched and still does its anti-ping-pong job; the ref is not
converted to state.
**Consequence:** the original fail-direction run described a code shape that did not
ship, so **a second fail arm was run against the as-shipped code** and is recorded above.
**Files:** `frontend/app/shop/shop-discovery-client.tsx`. **Commit:** `b3f8284d`.

### 2. [Rule 3 — blocking] The vendor route reads its realm base at REQUEST time

**Issue:** The plan said to mirror the customer route structurally; that sibling uses a
module-level `const`. A module-level constant is frozen at import, so the split-horizon
arm (b) — the single most important assertion in the route — **could not have been
written at all**, and the failure mode would have been an untestable claim rather than a
visible error.
**Fix:** `keycloakBase()` is a function called inside `GET`. This is also strictly more
correct at runtime: `frontend/Dockerfile:104-108` records that `NEXT_PUBLIC_KEYCLOAK_URL`
deliberately has no `ARG`/`ENV` precisely so it stays runtime-resolvable server-side.
**Commit:** `87597bee`.

### 3. [Rule 2 — missing critical functionality] `vendorLogout` guards `signOut` itself

**Issue:** The plan put `signOut` + navigate in a `finally`. If NextAuth's own `signOut`
throws, the whole `finally` aborts — the navigation never happens and the promise
rejects inside a button handler, which presents to a vendor as "nothing happened".
**Fix:** `signOut` has its own `try/catch` inside the `finally`; the navigation is
unconditional. Covered by a dedicated arm ("does not strand the vendor when NextAuth's
own signOut throws"). **Commit:** `87597bee`.

### 4. [Rule 2] The vendor route degrades when NEITHER Keycloak variable is set

**Issue:** Not specified by the plan. Both names are REQUIRED in `lib/env-validation.ts`,
so this is defence in depth — but an undefined base would have produced the literal string
`undefined/protocol/openid-connect/logout`.
**Fix:** `!idToken || !base` takes the same degraded branch: the sanitised app path, no
`id_token_hint`, no Keycloak host. **Commit:** `87597bee`.

### 5. [Rule 1 — bug] An eslint-disable that was measured unnecessary

**Found during:** the closing lint-inventory comparison.
**Issue:** `eslint-disable-next-line react-hooks/exhaustive-deps` above the hook's effect
became an `Unused eslint-disable directive` warning — the rule does not fire when there
is no dependency array at all.
**How it was caught, and why the total would have hidden it:** both the baseline and the
new tree printed `31 problems (0 errors, 31 warnings)`. The total was unchanged because
this change-set also *removed* one warning (`customerLogout`'s navigation target became a
variable rather than a string literal, so `@next/next/no-location-assign-relative-destination`
stopped firing). One added and one removed nets to zero. Only a **per-file, per-rule
inventory diff against a detached worktree at `24e82bfa`** named both. Now 30.
**Fix:** directive removed; the reasoning kept as prose plus a note recording the
measurement so it is not re-added. **Commit:** `892799e4`.

### 6. [cosmetic] Cookie-notice card absorbed the flex row

The plan described wrapper + card; the previous inner `mx-auto max-w-3xl flex …` row
would have made a third nesting level. Its flex classes moved onto the card instead.
No behaviour change; the wrapper/card pointer-events split is exactly as specified.

### 7. [minor addition] `data-entrance` is removed on cleanup

The plan specified setting the marker in-branch. It is also removed in the matchMedia
cleanup, matching `data-motion-active`'s treatment — the marker describes a scene that no
longer exists. `data-motion-decided` still flips to `"static"` there, unchanged.

### 8. [test hygiene] `runOnlyPendingTimers` wrapped in `act`

The R-02 suite's `afterEach` drains fake timers, which fires `next/link`'s
intersection-observer idle callback and produced a "not wrapped in act(...)"
`console.error` about `ForwardRef(LinkComponent)` — nothing to do with the component
under test. Wrapped in `act` so it does not train the next reader to ignore real ones.

### 9. Arms executed beyond those the plan required

- the `[]`-dependency break arm on the bottom-chrome hook, run **before** commit, because
  the plan warns that harness shape can be vacuous and a warning is not a measurement;
- an off-by-one (`<` for `<=`) arm on `entranceIsSafe`, isolating the inclusive boundary
  that the inversion arm alone would not have proven;
- CONTROL arms for the basket-items helper, the redirect sanitiser, the `offsetHeight`
  stub, and the pointer-events nesting.

### Non-deviation worth recording: one arm's RED proves less than it looks

The R-01 **route** tests' first red was a module-resolution failure — the route file did
not exist yet. That says nothing about whether any assertion in the file can fail, so it
is **not** counted as a fail direction. Arms (b) and (c), run against the shipped route,
are the real ones.

---

## Threat register disposition

| Threat | Disposition | Evidence |
|--------|-------------|----------|
| T-QF-01 tampering — `?redirect=` | mitigated | 6 hostile shapes + a CONTROL; arm (c) proves the assertion can name the attacker host |
| T-QF-02 info disclosure — `id_token_hint` | accepted, unchanged | front-channel by OIDC spec; the session already carried `idToken` to the browser via `buildSession` |
| T-QF-03 spoofing — SSO surviving sign-out (**P0**) | mitigated, front-channel only | route + `vendorLogout` + both call sites. **Residual stated, not implied:** a back-channel revoke (the customer path's `endCustomerIdpSession`) is strictly stronger and is NOT done here — recorded as follow-up |
| T-QF-04 DoS — stalled teardown | mitigated | 3s `AbortController` race on both client fetches, `AbortSignal.timeout(3000)` server-side, teardown in `finally` |
| T-QF-05 EoP — basket adopted by next user | mitigated | `clearStoredCarts` unconditional; asserted on ITEMS not key presence; the three pre-existing `cart-identity` arms untouched and green |
| T-QF-06 tampering — stranded custom property | mitigated | dedicated unmount-from-published arm, proven able to fail (arm d) |
| T-QF-07 DoS — notice occluding controls | mitigated (structurally) | pointer-events split + published offset; both classes proven able to fail. Browser `elementFromPoint` proof is the orchestrator's |
| T-QF-SC supply chain | n/a | **no package installed.** Every mechanism used was already in the tree |

## Threat flags

None. No new network endpoint reaches outside the app's own origin except the Keycloak
end-session URL, which is a browser navigation to an already-trusted, already-configured
IdP and carries only the `id_token_hint` that OIDC RP-initiated logout specifies.

## Known stubs

None. `TODO`/`FIXME` scan across all 21 code files in the change-set returns nothing
(rc=1), with a positive control confirming the pattern matches a fixture (rc=0). No
hardcoded empty value flows to a rendered surface.

---

## Owed to the orchestrator — NOT claimable from anything above

These are browser-level truths. A green Jest run is not evidence for any of them, and
this SUMMARY does not claim them.

1. **Rebuild ALL Compose images first.** `docker compose start` does not rebuild. Then
   prove parity by content and identity — per-service `.Metadata.LastTagTime` vs the
   newest commit touching that service's build paths, plus the running container's image
   ID vs the tag's. Only the frontend image is affected by this change-set.
2. **R-01 (the P0), the cookie-jar probe.** After a real vendor sign-out: zero Keycloak
   SSO cookies remain, and the next "Sign in with Keycloak" is **CHALLENGED for
   credentials**. Nothing here can say this — the unit tests assert the URL that is
   composed, not that a session was terminated.
3. **R-03, on a throttled profile** (4x CPU / Slow 4G). The h1 and persona CTAs stay
   visible throughout the landing load, with `data-entrance="skipped"` observed on the
   late-hydration run. **A screenshot cannot verify motion** — capture the timeline.
4. **R-07 symptoms (1)–(4), via `elementFromPoint`**: over the vendor sidebar Sign Out at
   desktop; over "Got it" at 390x844 with a non-empty basket; over "Browse all kitchens"
   on a zero-result `/shop`; over the landing CTA. Each must return the intended control
   and not the notice. Then **TAP "Got it" for real** and confirm the acknowledgement is
   written — a Playwright `click()` passes where a human tap fails, which is exactly why
   the existing suites never caught this.
5. **R-07 symptom (5):** the compacted copy does not truncate at 390px.
6. **R-02/R-09 in a real browser:** clear the search on all three affordances and confirm
   it stays cleared past 400ms; type fast enough to overlap requests and confirm the count
   matches the grid.

## Follow-ups filed by this work

- **Vendor back-channel logout.** `vendorLogout` is front-channel only (T-QF-03
  residual). The customer realm has `endCustomerIdpSession`; the vendor realm has no
  equivalent. If the browser abandons the navigation, the IdP session survives.
- **`hooks/use-bottom-chrome-height.ts` has one shared custom property.** Correct today
  because the two publishers live on disjoint surfaces and are never mounted together.
  A third bottom-fixed bar that could coexist with either would need a stacking rule.

## Self-Check: PASSED

- All 6 created files exist on disk (`FOUND` for each).
- All 5 commits exist in `git log 24e82bfa..HEAD`.
- All 5 declared `key_links` patterns found with `rg -uu -F --count-matches`
  (n=1,1,1,2,2), with a control pattern returning 0 in a file that must not contain it.
- `git diff --name-only 24e82bfa..HEAD` returns exactly the plan's 25 declared files.
- Working tree clean; branch 6 ahead of and 0 behind `origin/main`.

---
---

# Review Round — 260831-gnm-REVIEW.md (2026-08-31)

The review found **2 Critical, 7 Warnings, 3 Info**. Both Criticals were real and
both re-opened the P0 this branch exists to close. Six further commits.

| Hash | Findings | What it fixes |
|------|----------|---------------|
| `e80c01fb` | CR-01, IN-01 | bound the `signOut` step; encode the lookup redirect |
| `81c8eb29` | CR-02, WR-06 | in-flight latch + busy state on both vendor buttons; drop the customer `finally` |
| `06a4fa55` | WR-01 | first-mount latch so the hero entrance survives a soft nav |
| `bc0b9fe7` | WR-03, WR-02 | one `BottomNoticeShell` for notice AND consent banner; narrow an overstated claim |
| `4e052246` | WR-04, WR-07 | `no-store` + `Vary: Cookie` on both logout-url routes; state the origin residual |
| `07c7d238` | — | metrics regeneration; remove a `describe.each` the counter refuses |

## CR-01 — `vendorLogout` never navigated if `signOut` stalled

**The reviewer was right and I had the same defect twice on one branch.** The
lookup was bounded; `signOut` was not. `next-auth/react`'s `signOut` makes two
un-timeouted fetches, and because it was awaited INSIDE the `finally`, a stall
meant `window.location.href` on the next line never ran — vendor on the
dashboard, app session and all six SSO cookies alive, no feedback. That is the
R-04 defect verbatim, left on the P0 path while the customer sibling bounded
both of its fetches. The file's own docblock already claimed cover for it
("a failed or SLOW URL lookup must still … land them on /auth/signin"); the word
"slow" was honoured for one of the two steps.

**Fail (my own new arm, against the pre-fix code):**
```
✓ still signs out locally when the lookup NEVER SETTLES (8 ms)
✕ still navigates when NextAuth's signOut NEVER SETTLES (5001 ms)
  ● thrown: "Exceeded timeout of 5000 ms for a test.
Tests: 1 failed, 5 passed, 6 total
```
The five green arms beside it are the point: the suite covered a lookup that
stalls, a lookup that rejects, and a `signOut` that REJECTS. Nothing covered a
`signOut` that never answers.

**Re-run post-commit against the shipped code** (`settleWithin` replaced with the
old `try/await/catch`): identical, `5000 ms`, rc=1.
**Pass:** 6/6 then 9/9, rc=0.

`settleWithin` races a plain `setTimeout` and never rejects. Plain timer, not
`AbortSignal.timeout`, for the reason `fetchWithTimeout` already records. It
deliberately does not abort `signOut` — it cannot, and abandoning it is the point.

## CR-02 — a double-tap could cancel the federated logout

The reviewer traced it precisely and the break arm reproduced it exactly:

```
# latch removed
✕ returns the FIRST call's destination to both callers and never re-navigates
✕ holds the latch for a THIRD tap arriving after the first has resolved
    Expected: "http://localhost:8085/realms/jtoye-dev/protocol/openid-connect/logout?id_token_hint=ID"
    Received: "http://localhost:3000/auth/signin"
Tests: 2 failed, 7 passed, 9 total   rc=1
```
That `Received` line **is** the P0 returning: the second call names `/auth/signin`
and overrides the pending Keycloak navigation.

```
# disabled/aria-busy removed from BOTH vendor buttons
✕ goes disabled and aria-busy on the first tap, and STAYS that way (1038 ms)
✕ goes disabled and aria-busy on the first tap, and STAYS that way (1053 ms)
    Received element is not disabled:   (x2)
Tests: 2 failed, 2 passed, 4 total   rc=1
```
The two passing arms are the "enabled before the tap" controls, so the busy
assertions are measurements rather than a button that was never usable.

**Both halves shipped, deliberately.** The UI guard is what the vendor sees; the
module latch is what holds when the UI is bypassed — a keyboard repeat, a click
queued before the disable painted. Neither is ever reset: `location.href` only
SCHEDULES a navigation, so the document stays live and tappable for the whole
commit window, and resetting would re-open the race during exactly that window.

**WR-06** applies the same correction to the customer side, which had
`finally { setSigningOut(false) }` — re-enabling the button at the precise moment
`customerLogout` resolves, i.e. immediately after it assigns `location.href`.
```
# finally restored
✕ stays disabled after customerLogout RESOLVES (18 ms)
    Received element is not disabled:
Tests: 1 failed, 7 passed, 8 total   rc=1
```
Vendor and customer affordances now share one idiom instead of two contradictory ones.

## WR-01 — the reviewer's own suggested fix would have silently defeated R-03

The finding is correct: `performance.now()` counts from the document time
origin, so after any soft nav to `/` the budget was always exceeded and the
entrance was permanently `"skipped"` — a regression by omission under the
Incremental Betterment Doctrine, and `data-entrance` reported it as a correct
decision so an observation pass would read green either way.

**The proposed remedy was measured and rejected.** It suggested capturing
`mountedAt = performance.now()` in the effect and testing
`performance.now() - mountedAt`, on the reasoning that "for the hard-load case
this is identical to today's value in every way that matters". It is not — both
readings sit in the same synchronous effect body:
```
delta samples (ms): 0.0099, 0.0016, 0.0009, 0.0009, 0.0014
max delta: 0.0099
would entranceIsSafe(delta) be true?  true
```
Always inside any budget. That fix would have left the guard permanently open,
made `data-entrance` always report `"played"`, and undone R-03 while the code
still looked as though it had a budget. Implemented the coordinator's
first-mount latch instead, and recorded the measurement in the gate's docblock
so the same "simplification" is not proposed again.

**Fail (latch removed = today's behaviour):**
```
✕ SOFT NAV: a later mount plays the entrance even far past the budget
✕ SOFT NAV after a SKIPPED first mount also plays
✕ every mount from the third onwards is a soft nav too
✕ CONTROL: the reset really does re-arm the latch
    Expected: true / Received: false   (x4)
Tests: 4 failed, 19 passed, 23 total   rc=1
```
The first-mount budget arms stayed green — the exact split the defect has.
**Pass:** 23/23, rc=0.

The call also MOVED out of the `matchMedia` callback to mount scope: the callback
re-fires on every breakpoint change, which is not a new mount and must not
consume the latch — and a breakpoint change now reuses this mount's verdict
instead of re-playing an entrance over content already on screen.

## WR-03 / WR-02 — the consent banner bypassed the whole R-07 fix

Correct, and the compliance consequence is the sharp end: on that branch the
cart bar paints over the dismiss control, so the banner is permanently
un-dismissable and **the consent choice is unrecordable**. Dormant only because
nothing non-essential is registered today.

`BottomNoticeShell` now owns the contract and both surfaces render inside it.
The decisive test is not "the banner has the right classes" — the finding was
"there are two copies and only one got fixed" — so the arm compares the two
wrappers directly:
```
# banner reverted to its pre-fix shape
✕ carries the SAME bottom-chrome contract as the cookie notice
✕ shares ONE shell with the notice — asserted by comparing the two
    Expected: "fixed inset-x-0 z-40 pointer-events-none"
    Received: "fixed inset-x-0 bottom-0 z-40 border-t border-white/15 bg-oxblood text-cream px-4 pt-4 pb-[max(1rem,env(safe-area-inset-bottom))]"
Tests: 2 failed, 20 passed, 22 total   rc=1
```
**Pass:** 22/22, rc=0.

**WR-02 corrects an overstatement I wrote.** The cookie-notice docblock claimed
the pointer-events split "closes (1), (3) and (4) as a CLASS". It does not:
`pointer-events-none` removes click INTERCEPTION, it does not make the card
transparent, and a control the card is drawn over is still hidden. Precisely: the
split closes the interception class everywhere and closes the dashboard sidebar
case outright (the card is right-aligned from `sm` up); clearing a control the
card physically covers is the OFFSET's job, and the offset only acts where a
publisher exists — today the dashboard tab bar and the storefront cart bar, so
`/` and `/shop` still fall back to `bottom: 0px`. Corrected in place, because the
old wording would have told the next reader to stop looking.

## WR-04 / WR-07 — cache headers, and a residual stated rather than fixed

`Cache-Control: private, no-store, max-age=0` + `Vary: Cookie` on **both** exit
branches of **both** logout-url routes. `force-dynamic` governs rendering mode,
not the emitted header.
```
# headers dropped from the vendor route
✕ sends no-store and Vary: Cookie on the branch that carries the token
✕ sends the same headers on the degraded branch, so the two cannot drift
    Expected: "private, no-store, max-age=0" / Received: null   (x2)
Tests: 2 failed, 13 passed, 15 total   rc=1
```
The token-carrying arm asserts `id_token_hint` is really in the body first, so it
tests the branch it claims to. **Pass:** 45/45 across both route suites, rc=0.

**WR-07 is recorded, not fixed** — `resolvePublicOrigin`'s last fallback is
Host-derived outside a container. Inherited (the customer route consumed it
first), `NEXTAUTH_URL` is REQUIRED in `env-validation.ts`, and narrowing the
shared resolver is a separate, separately-tested change. The residual is now
stated in the vendor route so the next reader of the P0 path does not have to
rediscover it.

## The counter caught a defect in a test I had just written

`docs-freshness.sh --write` returned **rc=2 (VOID, not a pass)**:
```
ERROR: count-test-blocks.mjs could not count family 'jest' (rc=2):
VOID: …/vendor-signout-affordance.test.tsx:75: describe.each multiplies every
      block inside it; this counter cannot resolve that statically
      Treat this as UNVERIFIED, not as a pass.
```
That is the gate working. A two-row `describe.each` contributes two literal `it(`
tokens for four executed tests, so `docs/metrics.json` would have quietly
under-counted — the exact drift the two docs-freshness gates exist to prevent,
arriving through the door of a test written in this very round. Fixed at the
source (four literal `it(` blocks over shared helpers) rather than by extending
the counter, so the counted number and the executed number are the same number.
Cross-checked from the other end: jest's own `numTotalTests` reads **1566**,
matching the manifest exactly — the #582 deadlock satisfied from both sides.

## A restore FAILED mid-round, and only the closing arm caught it

Running the WR-01 break arm, I restored with
`git checkout HEAD -- lib/gsap-gate.ts` **before committing the fix**. `HEAD` was
therefore the state *before* `entranceIsSafeForMount` existed, so the restore
silently deleted the gate work while `hero-scene.tsx` and the test file kept
importing it. Verified by content rather than assumed:
```
entranceIsSafeForMount in gate:  rc=1   (absent)
firstMountPending in gate:       rc=1   (absent)
consumers still referencing it:  hero-scene.tsx:3, gsap-gate.test.ts:14
TypeError: (0 , _gsapgate.__resetEntranceMountGateForTests) is not a function
```
This is proof-standards §8 verbatim — "commit before running break arms, so the
restore target is a committed state" — and I broke it. The work was re-applied,
committed as `06a4fa55`, and the arm re-run against a committed target with the
restore then verified by content (`firstMountPending` present ×4, break token
absent) and a green closing run. **Recorded rather than quietly repaired**: the
rule exists because this failure is silent, and it was silent here too.

## Review findings NOT addressed (deferred, with reasons)

Scope was held per the coordinator's instruction. None is a live exploit; each is
recorded so it is not rediscovered.

- **WR-05 — three copies of the same-origin redirect narrower.** `safeReturnTo`
  (customer-auth), and a `sanitizeRedirect` in each logout-url route. The new copy
  is character-identical to the customer route's and strictly weaker than
  `safeReturnTo` (which also trims, rejects a backslash anywhere, and rejects any
  `scheme:` prefix). **Checked, not assumed:** neither of the reviewer's two extra
  cases escapes this route — `/foo\evil.example` normalises to a same-origin
  `/foo/evil.example`, and `"  //evil.example"` fails `startsWith("/")` and falls
  back. Maintenance finding: extracting a shared module touches three files and
  both routes' suites.
- **IN-02 — a superseded 429 leaves its retry timer armed.** The R-09 generation
  guard returns before the rate-limit branch, so a stale request's already-armed
  `retryTimerRef` is neither cleared nor its attempt counter reset. It fires
  against the CURRENT query, so the result is correct; the request is unintended
  and the backoff budget carries over. One line, but in a file this round does not
  otherwise touch and needing a fiddly fake-timer arm to prove.
- **IN-03 — `--jt-bottom-chrome` is a single-writer property with no ownership
  token.** Correct today only because the two publishers are never mounted
  together, which nothing enforces. Also cosmetic: `AnimatePresence`'s exit
  animation keeps the cart bar's height published for ~300 ms after the basket
  empties, so the notice hovers above nothing briefly. Needs a keyed/`Math.max`
  design and a two-publisher test.
- **T-QF-03 residual — vendor back-channel logout.** Unchanged from the first
  round and now doubly relevant: `vendorLogout` is front-channel only. The
  customer realm has `endCustomerIdpSession`; the vendor realm has no equivalent.

## Closing gates after the review round (clean tree)

| Gate | Result |
|------|--------|
| Full Jest | **145 suites / 1566 tests passed**, rc=0 |
| Coverage floor | 68.94 / 60.19 / 64.84 / 70.55 vs floor 63/55/60/64 — all clear, all up |
| ESLint | **0 errors**, 30 warnings, rc=0 (baseline was 31) |
| Type check | `rm -rf .next && npm run build` rc=0; vendor route emitted |
| docs-freshness | `OK: metrics match source (3555)`, rc=0 |
| doc metrics | `PASS: all 37 prose metric claim(s)`, rc=0 |
| branch vs base | `12 ahead, 0 behind` origin/main, rc=0 |
| working tree | clean (only untracked `.planning/` docs) |

Metrics moved to `jest_blocks 1566 / jest_files 145 / total 3555`;
`playwright_blocks` and `playwright_specs` **unchanged** at 127/27.

## Self-Check (review round): PASSED

- Both new files exist (`bottom-notice-shell.tsx`, `vendor-signout-affordance.test.tsx`).
- All 6 review-round commits exist in `git log`.
- Every fix has a fail-direction arm run against a **committed** target, with
  restores verified by content and a green closing run.
- Nothing outside the review's findings was changed.
