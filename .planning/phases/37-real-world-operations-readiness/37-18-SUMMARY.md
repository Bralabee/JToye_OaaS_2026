---
phase: 37-real-world-operations-readiness
plan: 18
subsystem: ui
tags: [kitchen-display, kds, session-lapse, next-auth, web-audio, alertdialog, inert, open-redirect, d-14, d-08, k2, next.js, react-19, playwright]

requires:
  - phase: 37-17
    provides: "useKitchenAlert with a `stopped` input and the one tap-unlocked AudioContext; deriveFeedState `signedOut`/`noAccess` pill rows; test-utils/fake-audio-context.ts; WINDOWS #31"
  - phase: 37-06
    provides: "SHOP_ACCESS_DENIED_EVENT from the api-client and the shell's no-access page that leaves the kitchen route alone"
provides:
  - "lib/session-lapse.ts: SESSION_LAPSED_EVENT = 'jtoye:session-lapsed', announceSessionLapse() (cancellable), navigateToSignIn(); the api-client redirects only when nobody claims the lapse"
  - "KdsBoardStopped (UI-SPEC K2): solid red-700 alertdialog, aria-modal, labelled and described, focus on the primary and trapped, the rest of <body> inert, static, overflow-y-auto; signed-out and access-ended variants; the cannot-ring line"
  - "markDeliberateSignOut / isDeliberateSignOut (module flag), set by the sidebar, the mobile More sheet and the overlay's own Sign out"
  - "playAlarmPattern + BOARD_STOPPED_ALARM (3 x 440 Hz square, 120 ms, 80 ms apart) every BOARD_STOPPED_ALARM_INTERVAL_MS = 3000 through the same shared context; useKitchenAlert `alarm` input and `soundEnabled` output"
  - "useSessionKeepAlive: getSession() SESSION_REFRESH_LEAD_MS (60 s) before the access token's expiresAt; accessTokenNeedsRefresh (60 s skew) in the jwt callback; the client session carries expiresAt"
  - "/auth/signin honours callbackUrl through lib/safe-return-to.ts; anything refused or absent is /dashboard"
  - "frontend/e2e/kitchen-session-lapse.spec.ts: live lapse, deliberate Sign out, INSTRUMENT arm, K2 backstop at 375x667/130% for both variants (5 test() blocks, 10 listed)"
affects: [37-22, 37-23, kitchen-display, auth]

actuals:
  tokens: 37032
  tasks: 3
  commits: 8

plan_head_before: 4e27e7714d8a185922cc67a0b2d360c12843d47b
plan_head_after: e3900e96ffd5bb9f5837dd342ed81616a492f5bb

tech-stack:
  added: []
  patterns:
    - "A cancellable window CustomEvent as the seam between a global client default (redirect) and the one route that must override it (claim with preventDefault, otherwise today's behaviour)"
    - "A modal overlay portalled to its own <body> child so every sibling can be made inert without making the overlay inert; focus trap kept for where inert is not honoured"
    - "Page-level jest tests over the REAL api-client with a scripted axios adapter, observing navigation the way jsdom reports it (\"Not implemented: navigation\") rather than a mocked seam"
    - "Every live layout assertion carries an INSTRUMENT step that breaks the page and requires the same measurement to see it"

key-files:
  created:
    - frontend/lib/session-lapse.ts
    - frontend/components/dashboard/kitchen/kds-board-stopped.tsx
    - frontend/components/dashboard/kitchen/use-session-keep-alive.ts
    - frontend/lib/__tests__/api-client-session-lapse.test.ts
    - frontend/components/dashboard/kitchen/__tests__/kds-board-stopped.test.tsx
    - frontend/components/dashboard/__tests__/kitchen-deliberate-signout.test.tsx
    - frontend/e2e/kitchen-session-lapse.spec.ts
  modified:
    - frontend/lib/api-client.ts
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/components/dashboard/kitchen/kds-sound.tsx
    - frontend/components/dashboard/sidebar.tsx
    - frontend/components/dashboard/mobile-tab-bar.tsx
    - frontend/app/auth/signin/page.tsx
    - frontend/app/auth/signin/__tests__/page.test.tsx
    - frontend/lib/session-callback.ts
    - frontend/__tests__/session-callback.test.ts
    - frontend/auth.ts
    - frontend/types/next-auth.d.ts

key-decisions:
  - "The lapse event name lives in lib/session-lapse.ts (the lib/access-events.ts precedent), imported by both the api-client and the kitchen page, so a test that auto-mocks the api-client still sees the real name; the literal 'jtoye:session-lapsed' is in that module, not duplicated in either end"
  - "Access ended is detected by listening to SHOP_ACCESS_DENIED_EVENT on the kitchen route rather than at each call site: on that route every API call is the board's own, so one listener covers the read, the detail, the bumps and the New lane's Accept/Reject inside its cards"
  - "Any stopped board (lapse or access) drops its STOMP topic and stops polling; a lapse outranks access ended (the pill's precedence)"
  - "The alarm needs a context that can sound but ignores the kds-muted preference: mute silences the chime, the overlay's Silence alarm silences the alarm (open to an owner ruling, deferred-items)"
  - "The cannot-ring sentence shows only when no tap ever created the context (soundEnabled false), never for a context the browser suspended later, so the overlay never claims 'never turned on' falsely"
  - "Silent refresh needs the jwt callback to refresh inside the token's last minute (accessTokenNeedsRefresh, 60 s skew); a refresh that fails while the old token is still valid keeps it. The keep-alive never declares a lapse itself: only the API's unrefreshable 401 does"
  - "The sign-in page reuses lib/safe-return-to.ts (stricter than the plan's single-slash rule: also refuses any backslash, control characters and a different origin after URL parsing) and reads the URL at click time, so the static page needs no Suspense boundary"

patterns-established:
  - "Kitchen K2 seam: lib/session-lapse.ts (event + navigation seam) -> api-client announces -> kitchen claims; markDeliberateSignOut before vendorLogout in every Sign out handler"
  - "Live e2e that ends a NextAuth session from the browser: clear the session-token cookies until /api/auth/session reads null twice, a beat apart (an in-flight session read re-issues the cookie)"

requirements-completed: [RWO-021, RWO-003]

coverage:
  - id: D1
    description: "A 401 the one silent refresh cannot fix dispatches a cancellable jtoye:session-lapsed; the kitchen claims it and shows K2 instead of redirecting (URL unchanged, pill 'Signed out', title 'STOPPED · Kitchen Display — J'Toye', chime silenced); unclaimed, every other route redirects to /auth/signin as before (F6)"
    requirement: RWO-021
    verification:
      - kind: unit
        ref: "frontend/lib/__tests__/api-client-session-lapse.test.ts (5) + kds-board-stopped.test.tsx#Kitchen page: a lapsed session stops the board loudly instead of redirecting (F6) (5): RED 14/16 at 0fd981e7 (RED_EVIDENCE_OK, evidence/37-18-red-task1.json), GREEN at b98a6608; arm F6 (preventDefault removed) red on 2, arm F6b (api-client ignores the claim) red on 2"
        status: pass
      - kind: e2e
        ref: "frontend/e2e/kitchen-session-lapse.spec.ts#lapse (mobile + desktop) live on frontend image 809cf75c: real SSO, cookie dropped, read triggered; overlay, /dashboard/kitchen, Signed out, STOPPED; INSTRUMENT arm (event swallowed) navigates to /auth/signin with no overlay (evidence/37-18-e2e-kitchen-session-lapse.txt, runs 2, 3, 5)"
        status: pass
    human_judgment: false
  - id: D2
    description: "KdsBoardStopped: solid red-700 alertdialog, aria-modal, labelled by the 36px headline and described by the body, focus on the primary and trapped, the rest of <body> inert and restored, nothing animates, overflow-y-auto so both 56px buttons stay reachable at 375x667 with 130% text, for both variants"
    requirement: RWO-021
    verification:
      - kind: unit
        ref: "kds-board-stopped.test.tsx#KdsBoardStopped: the board-stopped overlay (K2) (6): arm I (no inert) red on 2, arm T (no trap) red on 2, arm A (animate-pulse) red on 1"
        status: pass
      - kind: e2e
        ref: "kitchen-session-lapse.spec.ts#Kitchen: the board-stopped overlay fits a small screen at 130% text (K2 backstop) (2 tests x 2 projects) live 10/10; its INSTRUMENT (one-line headline) reads clipped=true; the first measurement (element scrollWidth) was caught vacuous by that INSTRUMENT on run 4 and replaced"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-18-screens/k2-{signed-out,access-ended}-{375x667-root130,1280x800}.png"
        status: pass
    human_judgment: false
  - id: D3
    description: "The alarm: three 440 Hz square tones, 120 ms each, 80 ms apart, every 3 s through the board's one unlocked AudioContext; Silence alarm stops the sound only; a board whose sound was never turned on still stops, says so in words and builds no AudioContext"
    requirement: RWO-021
    verification:
      - kind: unit
        ref: "kds-board-stopped.test.tsx#playAlarmPattern (2) + #Kitchen page: a stopped board rings the alarm (K2) (4): RED at 680dea1c (RED_EVIDENCE_OK, evidence/37-18-red-task2.json), GREEN at a67cf8f0; arms P (state ignored), Q (silence ignored), N (sentence hidden), N2 (sentence always) each red on 1"
        status: pass
    human_judgment: true
    rationale: "jsdom proves what the code asks the audio graph to do, not what a kitchen hears. Whether the alarm is audible over a kitchen and distinct from the chime is the 37-23 by-ear UAT row (WINDOWS #34)."
  - id: D4
    description: "A shop-access-denied 403 on a kitchen read or bump shows the access-ended variant ('Your access has ended: new orders are NOT showing', Sign out + Silence alarm), drops the STOMP topic (deactivating the socket), stops polling, pill 'No access' (D-08 on the kitchen route)"
    requirement: RWO-003
    verification:
      - kind: unit
        ref: "kds-board-stopped.test.tsx#Kitchen page: access ended stops the board (D-08) (4) over the real api-client: arm Z (no listener) red on 4, arm X (topic kept) red on 1, arm Y (poll kept) red on 1"
        status: pass
      - kind: e2e
        ref: "kitchen-session-lapse.spec.ts#access-ended variant at 375x667 (route-stubbed typed 403 through the real api-client): overlay + 'No access' pill, live 2/2"
        status: pass
    human_judgment: true
    rationale: "A REAL revoke ending a live board (and the open socket closing on it) needs a second session revoking a grant; not run live here (WINDOWS #34, residual T-37-47)."
  - id: D5
    description: "A deliberate Sign out (sidebar, mobile More sheet, the overlay's own Sign out) never shows the overlay or the alarm: the flag is set before vendorLogout and the kitchen leaves a later lapse or 403 unclaimed (P2-TUN-14)"
    requirement: RWO-021
    verification:
      - kind: unit
        ref: "kds-board-stopped.test.tsx#a deliberate sign-out shows no overlay and no alarm (3) + kitchen-deliberate-signout.test.tsx (2): arm D (claim-everything, the plan's) red on 3, arms S/S2 (handler does not mark) red on 1 each"
        status: pass
      - kind: e2e
        ref: "kitchen-session-lapse.spec.ts#a deliberate Sign out shows no overlay (mobile + desktop) live"
        status: pass
    human_judgment: false
  - id: D6
    description: "While the SSO session lasts the board asks for its session SESSION_REFRESH_LEAD_MS (60 s) before expiresAt; the jwt callback refreshes inside that minute; a refreshed session never shows the overlay; realm lifetimes unchanged"
    requirement: RWO-021
    verification:
      - kind: unit
        ref: "kds-board-stopped.test.tsx#useSessionKeepAlive (3) + session-callback.test.ts#accessTokenNeedsRefresh (37-18) (4): arms K (no lead) red on 2, K2 (ignores enabled) red on 1, K3 (page does not call it) red on 1, J (no skew), J2 (always refresh), J3 (unknown expiry kept), E (no expiresAt) each red on 1"
        status: pass
    human_judgment: true
    rationale: "Proven with fake timers and a unit rule only; a real 5-minute Keycloak token cycle on a mounted board was not observed (WINDOWS #34)."
  - id: D7
    description: "/auth/signin honours callbackUrl only as a same-origin path ('/dashboard/kitchen' kept; '//evil.example', 'https://evil.example', 'javascript:alert(1)', '/\\evil' and absent -> /dashboard), so Sign in again returns the tablet to the kitchen (F7, T-37-46)"
    requirement: RWO-021
    verification:
      - kind: unit
        ref: "app/auth/signin/__tests__/page.test.tsx#SignIn Page callbackUrl (37-18) (6): RED 1/24 at 84a68678 (RED_EVIDENCE_OK, evidence/37-18-red-task3.json), GREEN at 8af7b688; arm F7a (starts-with-slash only) red on 2, F7b (accept anything) red on 4, F7c (ignore it) red on 1"
        status: pass
      - kind: e2e
        ref: "kitchen-session-lapse.spec.ts#lapse: Sign in again -> /auth/signin?callbackUrl=%2Fdashboard%2Fkitchen -> SSO -> /dashboard/kitchen, live"
        status: pass
    human_judgment: false

duration: 40min
completed: 2026-10-10
status: complete
---

# Phase 37 Plan 18: The Kitchen Board Stops Loudly Summary

**A kitchen board whose session lapses now turns solid red with "Signed out: new orders are NOT showing" and an alarm, and stays on the kitchen route. A revoked grant gets its own variant and closes the socket. A deliberate Sign out does neither. "Sign in again" returns the tablet to the kitchen, and only to a same-origin path. The board refreshes its token a minute before expiry, so the red screen means a real lapse.**

## Performance

- **Duration:** about 40 min (start 2026-10-10T13:20:34Z, end about 14:00Z)
- **Tasks:** 3 of 3. Task 1 is a tracer: re-verified after commit, then expanded.
- **Files:** 18 frontend files (7 created, 11 modified), plus evidence.

## Accomplishments

- **UXT-021's repro now fails before and passes after.**
  - The jest repro is "an unrefreshable 401 on a kitchen read shows the overlay, never the sign-in page". It runs the real kitchen page over the real api-client. It was red at `0fd981e7` and is green at `b98a6608`.
  - The live repro is `kitchen-session-lapse.spec.ts`: real SSO, the NextAuth cookie dropped, a read triggered. It shows the overlay, the URL stays `/dashboard/kitchen`, the pill reads "Signed out" and the title reads "STOPPED".
- **The api-client's redirect is now claimable.** On every other route it behaves exactly as before; arm F6b proves the test can see the difference.
- **K2 matches the UI-SPEC.** It is a solid red-700 alertdialog with white text, the OctagonAlert icon, a 36px/600 headline, 16px body copy and 56px buttons. The rest of the page is inert, focus is on the primary and trapped, and nothing animates. It fits 375x667 at 130% text for both variants, measured live.
- **The alarm** is three 440 Hz square tones every 3 s, through 37-17's one tap-unlocked context. "Silence alarm" stops the sound and leaves the red screen. If sound was never turned on, the overlay says so, and no context is built outside a gesture.
- **Access ended (D-08 on the kitchen route).**
  - Any typed shop-access-denied 403 on the route shows "Your access has ended: new orders are NOT showing".
  - It drops the STOMP topic, which deactivates the socket, and it stops polling.
  - Its Sign out counts as a deliberate sign-out.
- **Deliberate sign-out (P2-TUN-14).** The sidebar and the mobile More sheet set the flag before `vendorLogout()`. The mobile sheet matters because a kitchen tablet below `md` has no sidebar.
- **Silent refresh (D-14).**
  - `useSessionKeepAlive` asks for the session 60 s before `expiresAt`.
  - The jwt callback refreshes inside that minute, and keeps a still-valid token if a refresh blips.
  - The client session now carries `expiresAt`.
  - Realm lifetimes are unchanged.
- **Safe return.** `/auth/signin` honours `callbackUrl` through the one same-origin sanitiser.
- **WINDOWS #31 is closed** (fixed, `a67cf8f0`). All three of 37-17's inputs (`stopped`, `signedOut`, `noAccess`) are wired.

## Task Commits

1. **Task 1 (tracer): a lapse on the kitchen route shows the overlay instead of redirecting.** RED `0fd981e7`, GREEN `b98a6608`.
2. **Task 2: the alarm, access ended, deliberate sign-out and silent refresh.** RED `680dea1c`, GREEN `a67cf8f0`.
3. **Task 3: a safe return to the kitchen, and the live lapse spec.** RED `84a68678`, GREEN `8af7b688`.
4. **Verification fixes to the live spec:**
   - `61b556fd`: the NextAuth cookie race and the #593 live-tree scoping.
   - `e3900e96`: the K2 backstop at 375x667/130%, both variants.

The plan metadata commits follow this SUMMARY.

## TDD Gate Compliance

| Task | RED (evidence, classifier) | GREEN | REFACTOR |
|------|----------------------------|-------|----------|
| 1 | `0fd981e7`: TAP 14/16 (`37-18-red-task1.json`), RED_EVIDENCE_OK | `b98a6608` | not needed |
| 2 | `680dea1c`: TAP 15/36 (`37-18-red-task2.json`), RED_EVIDENCE_OK | `a67cf8f0` | not needed |
| 3 | `84a68678`: TAP 1/24 (`37-18-red-task3.json`), RED_EVIDENCE_OK | `8af7b688` | not needed |

**Semantic assessment (RED).** In each record the named target executed and failed on its planned assertion:

- **Task 1:** `waitFor(alertdialog not null)` received null after the lapsed read.
- **Task 2:** the same wait received null after the typed 403.
- **Task 3:** `toHaveBeenCalledWith` expected `/dashboard/kitchen` and received `/dashboard`.

Each RED commit carried source interface stubs, as in 37-17, so the suites loaded and failed on assertions rather than imports.

**Tests that were green on the RED tree, and the arm that proves each can fail:**

- **Task 1:** the two today-behaviour guards (a refreshed 401 and a 403 announce nothing). Arm F6b covers the claim path.
- **Task 2:**
  - Alarm on a suspended context plays nothing: arm P.
  - Silence with no alarm playing: arm Q.
  - A sound-on board makes no cannot-ring claim: arm N2.
  - A refused read after Sign out shows nothing: arm D.
  - The keep-alive asks nothing when disabled: arm K2.
  - The two jwt guards: arms J2 and J3.
- **Task 3:** the four hostile URLs and the no-parameter case. Arms F7a and F7b cover them.

## Verification

**Tracer `<verify>`, re-run after commit.** `npm --prefix frontend test -- lib/__tests__/api-client-session-lapse.test.ts components/dashboard/kitchen/__tests__/kds-board-stopped.test.tsx` passed 16/16, rc 0. ⚡ Tracer verified end-to-end, then expanded.

**Task 2 `<verify>`.** `npm --prefix frontend test -- components/dashboard/kitchen app/dashboard/kitchen components/dashboard/__tests__` passed 341 tests in 21 suites, rc 0.

**Task 3 `<verify>`.**

- `npm --prefix frontend test -- app/auth/signin` passed 24/24.
- `npm run build` gave rc 0.
- `npx playwright test e2e/kitchen-session-lapse.spec.ts --list` gave rc 0, "Total: 10 tests in 1 file". It was 6 before the backstop tests were added.

**Break arms.** Every arm ran on a committed file and was restored from a scratch copy, verified by sha256, with a clean run closing each set. The full log is in `evidence/37-18-break-arms.txt`.

| Criterion | Break | Result |
|-----------|-------|--------|
| F6: the lapse shows K2, not a redirect | the page no longer calls `preventDefault` | 2 red: defaultPrevented false, navigation attempted |
| F6: unclaimed means today's redirect | the api-client always redirects | 2 red |
| Inert, focus trap, static | inert removed / listeners removed / `animate-pulse` added | 2 / 2 / 1 red |
| Deliberate Sign out (plan's arm) | claim-everything variant | 3 red |
| Sign out handlers mark first | the sidebar or the mobile sheet does not mark | 1 red each |
| Alarm | state ignored / silence ignored / sentence hidden / sentence always | 1 red each |
| Access ended | no listener / topic kept / poll kept | 4 / 1 / 1 red |
| Silent refresh | lead 0 / ignores enabled / page does not call it | 2 / 1 / 1 red |
| jwt skew and expiresAt | no skew / always refresh / unknown expiry kept / expiresAt dropped | 1 red each |
| F7: same-origin callbackUrl | starts-with-slash only / accept anything / ignore it | 2 / 4 / 1 red |
| Build type gate | `ALARM_SILENCED_LABEL` typed as `BoardStoppedReason` | rc 1, TS2322; clean rc 0 with "Finished TypeScript" |
| Lint error gate | a bare `role="alertdialog"` div | still 0 errors: **this arm could not fail** (jsx-a11y has no rule for it), recorded and replaced |
| Lint error gate (replacement) | `<img>` without alt | rc 1, 1 error (`jsx-a11y/alt-text`); clean 0 errors |

**Full frontend suites on the final tree.**

- **jest:** 2516 passed in 213 suites, rc 0.
  - Baseline at `4e27e771`, re-measured from a full-repo `git archive` with node_modules linked: 2472 in 210, rc 0.
  - A frontend-only archive gave 14 false failures, because suites read `docs/` and `.env.example`. The full-repo archive was used instead.
  - The +44 tests are: 5 api-client, 27 board-stopped, 2 deliberate sign-out, 4 session-callback and 6 sign-in.
  - `count-test-blocks` counts the touched files as 5/27/2/7/24, equal to the executed counts. The 37-17 VOID control returns rc 2.
- **build:** rc 0. TypeScript ran and finished.
- **lint:** 0 errors, 31 warnings, the same as the baseline. The one `no-location-assign` warning moved from `api-client.ts` to `session-lapse.ts`.
- **e2e typecheck** (`tsconfig.e2e.json`): rc 0.
- **core-java:** untouched; no Java file changed, so its suites were not run.

**Live run.** Only the frontend was touched on the stack.

- **Rebuild.** `docker compose build frontend` produced image `809cf75c` (tagged 13:45:58Z). `up -d --no-deps --force-recreate frontend` replaced the container: `aaf2fc30` (image `7e5a7401`) became `c5e633c4`, started 13:46:05Z, healthy. Core-java's image `94e8e7b2` (10:30:05Z) and its container `b5085080` are unchanged, measured before and after.
- **Freshness by content.** Read inside the running container, under `/app/.next`:
  - "new orders are NOT showing": 2 files.
  - `jtoye:session-lapsed`: 4.
  - "STOPPED · Kitchen Display": 2.
  - The cannot-ring line: 2.
  - `callbackUrl=%2Fdashboard%2Fkitchen`: 2.
  - Positive control, a 37-17 string: 2.
  - Negative control, an absent token: 0.
  - The old image had already been pruned, so the "old image lacks it" direction is VOID (docker rc 125) and is recorded.
- **`scripts/check-runtime-freshness.sh`** exits rc 1: `frontend FRESH image tagged 2026-10-10 13:45:58 UTC >= newest build-input commit 8af7b688`; edge-go and mcp-server FRESH; `core-java DRIFT [image-not-rebuilt] ... newest build-input commit 935bf0a6`; "FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified)". The core-java DRIFT is the known stale image (WINDOWS #30, #33) and was not rebuilt, as instructed. Re-run after the spec commits: unchanged, because `e2e/` is not a frontend build input.
- **`e2e/kitchen-session-lapse.spec.ts`**, run live with real SSO, as `admin-user`:
  - Run 1: 4 of 6 red (spec defects, below).
  - Run 2: 6/6.
  - Run 3, with `--repeat-each=2`: 12/12.
  - Run 4: 4 red, all of them the new INSTRUMENT catching a vacuous measurement.
  - Run 5: 10/10.
- **Screenshots** of both variants at 375x667/130% and 1280x800 are in `evidence/37-18-screens`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The silent refresh needed the session read to refresh before expiry, and the browser to know the expiry**

- **Found during:** Task 2, reading `auth.ts` (the plan's `read_first`).
- **Issue:** The jwt callback kept a token until it had expired, so a `getSession()` 60 s before `expiresAt` returned the same token. The client session did not carry `expiresAt` at all.
- **Fix:**
  - `accessTokenNeedsRefresh` applies a 60 s skew, and `auth.ts` uses it.
  - A refresh that fails while the old token is still valid keeps that token, so a blip does not sign anyone out early.
  - `buildSession` copies `expiresAt`, and `Session.expiresAt` was added to the types.
- **Files:** `frontend/auth.ts`, `frontend/lib/session-callback.ts`, `frontend/types/next-auth.d.ts`, `frontend/__tests__/session-callback.test.ts`. None of these is in the plan's file list.
- **Verification:** arms J, J2, J3 and E. The refresh rule applies to every route: a token is refreshed in its last minute instead of after it. No user-visible change on other routes.
- **Commits:** `680dea1c`, `a67cf8f0`.

**2. [Rule 2 - Correctness] The mobile More sheet's Sign out marks the deliberate flag too**

- **Issue:** The plan names only the sidebar. A kitchen tablet below `md` has no sidebar, so it signs out from the mobile sheet.
- **Fix:** `mobile-tab-bar.tsx` calls `markDeliberateSignOut()` before `vendorLogout()`, and a test covers it (arm S2).
- **Commit:** `a67cf8f0`.

**3. [Structure] Two small new modules**

- `lib/session-lapse.ts` holds the event name, `announceSessionLapse` and the `navigateToSignIn` seam (the `access-events.ts` precedent).
- `use-session-keep-alive.ts` holds the timer hook.
- The plan put the event inline in `api-client.ts`. The literal now lives in one module that both ends import, so it cannot drift.
- **Commits:** `0fd981e7`, `a67cf8f0`.

**4. [Structure] Access ended uses the route-wide `SHOP_ACCESS_DENIED_EVENT` listener rather than per-call-site error mapping**

- This covers the New lane's Accept and Reject, which handle their errors inside the card.
- It is proven over the real api-client, so the event is genuinely dispatched by the interceptor and not hand-fired.
- **Commit:** `a67cf8f0`.

**5. [Rule 1 - Bug, test] The keep-alive test measured the lead from the test's start instead of the whole-second `expiresAt` it was given**

- **Issue:** Off by the sub-second fraction (239419 against 240000).
- **Fix:** The assertion is now against the recorded expiry. Its intent is unchanged.
- **Commit:** `a67cf8f0`.

**6. [Rule 1 - Bug, spec] The live spec as first committed was red 4/6 on the live stack, for two spec defects**

- **Defect a, #593:** the streaming buffer's second copy of the board made `getByTestId('kds-sound-control')` resolve to 2 elements.
- **Defect b, a NextAuth race:** NextAuth re-issues the session cookie on every session read, so a `/api/auth/session` request in flight when the cookie was cleared brought the session back. The next read got 200.
- **Diagnosis:** a throwaway spec, deleted. With a 3 s settle, the product path worked: 401, claimed, one alertdialog, URL kept.
- **Fix:** live-tree scoping, and a clear-until-`null`-twice helper.
- **Commit:** `61b556fd`.

**7. [Added] The UI-SPEC K2 long-text backstop and the plan's overflow assumption, proven live**

- **Issue:** The must-have's verification is "backstop: Playwright at 375x667". The plan's Task 3 spec did not include it.
- **Fix:** Added it, for both variants, with an INSTRUMENT step. On run 4 that step caught the first measurement (each element's own scrollWidth) as vacuous: the flex-item headline grows to fit, reading 930/930 on one line. It was replaced with viewport containment.
- **Commit:** `e3900e96`.

**Total deviations:** 7. Of these, 3 are Rule 1 (two test and spec defects found by running them), 1 is Rule 2, 1 is Rule 3, and 2 are structural or added coverage.

**Impact:**
- All but #1 are confined to the kitchen, the sign-in page, the two Sign out handlers, and tests.
- #1 changes when every route refreshes its access token: a minute earlier.
- No API or schema change.

## Issues Encountered

See `deferred-items.md` § "From 37-18". In short:

- **The board will stop at the SSO maximum (2 h)**, because D-14 keeps the realm lifetimes; expect it at the 37-23 UAT.
- **The alarm ignores mute.** This is a decision, open to an owner ruling.
- **At 375x667/130% the overlay opens scrolled to its focused primary.**
- **A deliberate sign-out on a non-kitchen route may race the api-client's redirect.** This is pre-existing and not measured.
- **The kitchen treats every shop-access-denied 403 on its route as access ended.**
- **A bare `tsc` shows one pre-existing 37-17 error.**
- **The old frontend image was pruned before it could be read.**

## Known Stubs

None. WINDOWS #31, 37-17's stub, is closed by this plan.

## Threat Flags

None new.

- **T-37-46 (open redirect) is mitigated.** It goes through the one sanitiser; arms F7a, F7b and F7c cover it.
- **T-37-48 (a silent kitchen after a lapse) is mitigated.** The claimed event, the overlay, the alarm, the silent refresh and the live spec cover it.
- **T-37-47 (frames to a revoked user until the socket closes) is accepted, as planned.** The board now drops its topic on the first 403, so the client closes the socket itself. Until then, an open socket can still deliver `OrderStateChangeEvent` frames (order id, number and status), because the server checks only on SUBSCRIBE. This is the recorded residual.
- **T-37-SC:** no package was installed.
- **One new auth-adjacent surface:** the client session now exposes the access token's `expiresAt`. It is not a credential, because the token it describes carries the same `exp`.

## User Setup Required

None.

## Next Phase Readiness

- **37-22 runs next.**
  - The frontend runtime is fresh at `8af7b688` (image `809cf75c`).
  - Core-java is still pre-V80 and pre-37-20 (WINDOWS #30, #33).
- **For 37-23:**
  - Re-run `e2e/kitchen-session-lapse.spec.ts`: 10 tests, green live here.
  - Do the by-ear alarm check, the real-revoke check and a real token-cycle check (WINDOWS #34).
  - Expect the 2 h SSO stop.
- **Requirements:**
  - RWO-003 is marked complete. `ready-ids` reports 37-18 as its last declaring plan, and 37-15 had left it open for exactly that reason.
  - RWO-021 stays blocked, because 37-23 also declares it.

## Self-Check: PASSED

- FOUND: every `key-files.created` path, `evidence/37-18-red-task{1,2,3}.json`, `evidence/37-18-break-arms.txt`, `evidence/37-18-e2e-kitchen-session-lapse.txt`, and the 4 PNGs in `evidence/37-18-screens/`.
- FOUND, as ancestors of HEAD: `0fd981e7`, `b98a6608`, `680dea1c`, `a67cf8f0`, `84a68678`, `8af7b688`, `61b556fd`, `e3900e96`.
- MEASURED: `git rev-list --count 4e27e771..e3900e96` = 8. `gsd_run check evaluation-scope --plan 37-18 --commits-only` resolved the plan's commits, rc 0.
- The plan's three `<verify>` commands and its `<verification>` re-ran green, as listed above.
