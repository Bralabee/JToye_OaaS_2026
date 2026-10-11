---
phase: 37-real-world-operations-readiness
plan: 17
subsystem: ui
tags: [kitchen-display, kds, web-audio, autoplay, feed-pill, d-12, d-14, sc-1, next.js, react-19]

requires:
  - phase: 37-16
    provides: "the New lane (newOrders in page.tsx), Accept/Reject, the 1 Hz ring that runs regardless of mute"
provides:
  - "useKitchenAlert: one AudioContext created only by enableSound() in a tap; the two-tone chime every NEW_ORDER_ALERT_INTERVAL_MS while this shop's New lane holds a PENDING order"
  - "KdsSoundControl (labelled, h-11) and KdsSoundBar (non-dismissible 'Sound is off' / 'Alerts are muted')"
  - "kitchenTabTitle: '(n) New orders · Kitchen Display — J'Toye'"
  - "contextCanSound: getAutoplayPolicy feature-detected, AudioContext.state fallback"
  - "deriveFeedState.pill + KdsFeedPill ten-state precedence (Signed out, No access, Out of date, Sound off, Muted added) with a constant-width label slot"
  - "test-utils/fake-audio-context.ts: a recording AudioContext for jsdom"
affects: [37-18, 37-23, kitchen-display]

actuals:
  tokens: 15787
  tasks: 3
  commits: 6

plan_head_before: f3cf9f0a47352abcd0db52ba1f69b781896358fc
plan_head_after: 54af268d2ad6ce5d80aad5aec0c2e44493d47d65

tech-stack:
  added: []
  patterns:
    - "Web Audio unlocked only inside a tap handler; the state is read from the context on statechange, never assumed from the tap"
    - "A status pill's width fixed by stacking an invisible pseudo-element sizer per label in one grid cell (no text added, real font decides)"
    - "A recording fake for a browser API with no jsdom implementation, so behaviour is asserted as what the code ASKS the API to do"

key-files:
  created:
    - frontend/components/dashboard/kitchen/kds-sound.tsx
    - frontend/test-utils/fake-audio-context.ts
    - frontend/components/dashboard/kitchen/__tests__/kds-sound.test.tsx
    - frontend/app/dashboard/kitchen/__tests__/kitchen-alert.test.tsx
  modified:
    - frontend/app/dashboard/kitchen/page.tsx
    - frontend/components/dashboard/kitchen/feed-state.ts
    - frontend/components/dashboard/kitchen/kds-feed-status.tsx
    - frontend/components/dashboard/kitchen/__tests__/feed-state.test.ts
    - frontend/components/dashboard/kitchen/__tests__/kds-feed-status.test.tsx
    - frontend/app/dashboard/kitchen/__tests__/page.test.tsx
    - frontend/e2e/kitchen-flow.spec.ts

key-decisions:
  - "The chime counts only the selected shop's PENDING orders (shopId match), so a shop switch silences the old board before the new read lands; the tab title counts the whole New lane, in the same render as the lane"
  - "soundState = muted ? 'muted' : context can sound ? 'on' : 'off'. A persisted mute wins over 'no context yet', which is what lets F4's muted bar and Muted pill show on load"
  - "'Unmute alerts' is itself the gesture: one tap unmutes AND unlocks the context"
  - "The pill precedence is a NEW deriveFeedState field 'pill'; 'status', 'stale' and 'alerting' keep their meaning, so sound and mute raise no feed banner and every existing four-state assertion is untouched"
  - "The pill's label slot stacks a pseudo-element sizer per label instead of guessing a min-w: measured 240 px in all ten states; the old min-w-[5.5rem] let it vary 230-240 px"
  - "The page's 'stopped', 'signedOut' and 'noAccess' inputs are false until 37-18 wires K2 (WINDOWS #31); the hook and the pill already honour them under test"

patterns-established:
  - "Kitchen sound: one shared AudioContext per board, created in a tap handler; 37-18's alarm reuses it (playNewOrderChime sits beside where the alarm goes)"
  - "Live checks that need only the frontend: docker compose build frontend + up -d --no-deps --force-recreate frontend (core-java's tag is untouched, measured)"

requirements-completed: [RWO-020, RWO-021]

coverage:
  - id: D1
    description: "A PENDING order on this board's New lane rings a two-tone chime (880 then 660 Hz, 150 ms each, sine, gain 0.6) at once and every 5 s through ONE AudioContext created inside the 'Turn on sound' tap; it stops on accept/reject of the last order, mute, shop switch or a stopped board; no AudioContext is ever built outside a tap (playBeep removed)"
    requirement: RWO-020
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/kitchen/__tests__/kds-sound.test.tsx#useKitchenAlert (D-12) (8 tests): RED at dad7525d (RED_EVIDENCE_OK, evidence/37-17-red-repeating-chime.json, 6/8), GREEN at 6270cd8b; arm A (ring once, no interval) red on 3, arm B (context built at mount) red on 4, arm C (pending gate removed) red on 4"
        status: pass
      - kind: unit
        ref: "frontend/app/dashboard/kitchen/__tests__/kitchen-alert.test.tsx#Kitchen page: the repeating new-order alert (4 tests): RED 4/4 at dad7525d (RED_EVIDENCE_OK, evidence/37-17-red-page-alert.json; the socket frame built an AudioContext), GREEN at 6270cd8b; arm D (alert counts every shop) red on the shop-switch test"
        status: pass
      - kind: other
        ref: "rg -uu -n 'new AudioContext' frontend/app/dashboard/kitchen/page.tsx: rc 0 with one hit (l.114) before, rc 1 with no output after"
        status: pass
    human_judgment: true
    rationale: "Sound cannot be proven by jsdom or a screenshot. Whether the chime is audible and distinct in a noisy kitchen, and whether 20 stale dev orders ringing every 5 s is tolerable, is the phase UAT's (37-23 VALIDATION manual-only row)."
  - id: D2
    description: "The tab title reads '(n) New orders · Kitchen Display — J'Toye' while n > 0 and 'Kitchen Display — J'Toye' otherwise, from the lane count in the same commit; restored on leaving the page"
    requirement: RWO-020
    verification:
      - kind: unit
        ref: "kitchen-alert.test.tsx#puts the New-lane count in the tab title, and drops it when the lane empties: RED at dad7525d (title ''), GREEN at 6270cd8b"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-17-screens + live read on frontend image 7e5a7401: document.title '(20) New orders · Kitchen Display — J'Toye' on the dev tenant at 390 and 1280 px"
        status: pass
    human_judgment: false
  - id: D3
    description: "Sound state shown, never assumed: labelled h-11 control (VolumeX 'Turn on sound' / Volume2 'Mute alerts' / BellOff 'Unmute alerts' amber-800); a non-dismissible amber bar 'Sound is off. New orders will flash but not ring.' or 'Alerts are muted. …' with one h-11 button; a persisted kds-muted shows on load (F4); a context suspended later reads off; getAutoplayPolicy feature-detected with AudioContext.state as fallback; the flash runs while muted; the kds-muted key and lifetime unchanged"
    requirement: RWO-020
    verification:
      - kind: unit
        ref: "kds-sound.test.tsx (12 Task 2 tests) RED at 4811abd5 (RED_EVIDENCE_OK, evidence/37-17-red-sound-state.json, 10/20), GREEN at 13b0c0c6; arms E (muted bar hidden, F4) red on 5, F (no statechange listener) red on 2, G (policy ignored) red on 2, H2 (state ignored) red on 3, J (dismiss button added) red on 2"
        status: pass
      - kind: unit
        ref: "kitchen-alert.test.tsx#Kitchen page: the sound state is shown (4 tests) RED 3/8 at 4811abd5 (RED_EVIDENCE_OK, evidence/37-17-red-sound-bar-page.json), GREEN at 13b0c0c6; arm I (New card flash removed) red on the muted-flash test"
        status: pass
      - kind: e2e
        ref: "frontend/e2e/kitchen-flow.spec.ts 18/18 live on frontend image 7e5a7401 (evidence/37-17-e2e-kitchen-flow.txt): the control reads Turn on sound, then Mute alerts, then Unmute alerts; the pre-change spec against the same runtime fails toHaveCount(1) on the old mute button (evidence/37-17-e2e-kitchen-flow-prechange-spec.txt)"
        status: pass
    human_judgment: false
  - id: D4
    description: "The feed pill says 'Live' only when it is true: precedence Connecting… > Signed out > No access > Offline > Not updating > Reconnecting > Out of date > Sound off > Muted > Live; the existing four words kept; constant width"
    requirement: RWO-021
    verification:
      - kind: unit
        ref: "kds-feed-status.test.tsx#KdsFeedPill: the ten-row precedence table (F3) + feed-state.test.ts#deriveFeedState: the pill's precedence: RED 23/70 at 6e2b8ced (RED_EVIDENCE_OK, evidence/37-17-red-pill-precedence.json), GREEN at 54af268d; arm K (precedence reverted so live wins, the plan's F3 arm) red on 22, arm L (Sound off/Muted swapped) red on both pair tests, arm M (sizers dropped) red on the width test; pre-existing assertions in both files unchanged (diff: one import line)"
        status: pass
      - kind: unit
        ref: "kitchen-alert.test.tsx#the feed pill says 'Live' only when it is true (3 tests): RED 3/11 at 6e2b8ced (RED_EVIDENCE_OK, evidence/37-17-red-pill-page.json), GREEN at 54af268d"
        status: pass
      - kind: automated_ui
        ref: "evidence/37-17-browser-probe.json: the real pill markup + the built CSS + Work Sans in Chromium 153, 240 px in all ten states at 390 and 1280; 230-240 px with the sizers removed (fail direction). Live board: pill 240 px reading Sound off / Live / Muted"
        status: pass
    human_judgment: false
  - id: D5
    description: "Signed out / No access pill rows exist and are tested, but the page passes them false until 37-18 wires the K2 overlay (WINDOWS #31)"
    requirement: RWO-021
    verification:
      - kind: unit
        ref: "kds-feed-status.test.tsx rows 2 and 3 + feed-state.test.ts 'session lapsed' / 'access ended' (deriveFeedState level)"
        status: pass
    human_judgment: true
    rationale: "The inputs are not wired on the page in this plan by design; the end-to-end lapse and access-ended paths are 37-18's to prove."

duration: 25min
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 17: Repeating Kitchen Alert and Truthful Feed Pill Summary

**A new order now keeps calling until someone accepts it. A two-tone chime plays through one AudioContext, unlocked by a "Turn on sound" tap, and repeats every 5 s while an order waits. The board says in words when it cannot ring. The tab title carries the count. The feed pill reads "Live" only when nothing in the ten-row precedence says otherwise.**

## Performance

- **Duration:** about 25 min (start 2026-10-09T13:00:15Z, end about 13:25Z)
- **Tasks:** 3 of 3. Task 1 is a tracer: it was re-verified, then expanded.
- **Files:** 11 source and test files, plus the e2e spec and evidence.

## Accomplishments

- **D-12, UXT-020 (sound half).** The old `playBeep` built a fresh `AudioContext` inside the socket callback, and swallowed every error. A real browser suspends a context created that way. Chromium 153 was measured: a context created without a gesture is `suspended`, and one created in a click is `running`. So a new order rang at most once, and possibly never. Now:
  - `useKitchenAlert` owns one context, which only the tap creates.
  - The chime repeats every `NEW_ORDER_ALERT_INTERVAL_MS` (5000).
  - It stops on accept or reject of the last order, on mute, on a shop switch and on a stopped board.
- **Sound state, shown and never assumed.**
  - The header control has words.
  - A non-dismissible amber bar says why the board cannot ring. Its one button is the fix.
  - A persisted mute is visible on load (F4).
  - A context the browser suspends later turns the state back to off.
  - `getAutoplayPolicy` is feature-detected. Chromium 153 does not have it, so on Chromium `AudioContext.state` decides.
- **Tab title.** It reads "(20) New orders · Kitchen Display — J'Toye" on the live dev tenant.
- **D-14, SC-1 (pill half).** Five states are added to the four existing ones: Signed out, No access, Out of date, Sound off and Muted. `deriveFeedState` applies them in precedence order.
  - The pill is now a constant 240 px in every state, measured in a real browser with the built CSS.
  - The old pill moved by up to 10 px between "Live" and "Reconnecting". This was a latent #536-class shift that nobody had measured.
- **Goods kept.**
  - The `kds-muted` key and its lifetime are unchanged.
  - The flash runs while muted. Arm I proves the test can see it stop.
  - The banner's four states and words are unchanged, and sound and mute raise no feed banner.
  - The "Connecting…" first-paint behaviour is unchanged.
  - Every pre-existing pill and derivation assertion is unchanged. The diff to those two test files is one import line.
  - The 37-16 lane, Accept and Reject are untouched, and `kitchen-flow` passes 18/18 live.

## Task Commits

1. **Task 1 (tracer): the chime repeats until the order is accepted.** RED `dad7525d`, GREEN `6270cd8b`.
2. **Task 2: the sound state is shown, never assumed.** RED `4811abd5`, GREEN `13b0c0c6`.
3. **Task 3: the feed pill says "Live" only when it is true.** RED `6e2b8ced`, GREEN `54af268d`.

The plan metadata commit follows this SUMMARY.

## TDD Gate Compliance

| Task | RED (evidence, classifier) | GREEN | REFACTOR |
|------|----------------------------|-------|----------|
| 1 | `dad7525d`: TAP 6/8 (`37-17-red-repeating-chime.json`) and 4/4 (`37-17-red-page-alert.json`), both RED_EVIDENCE_OK | `6270cd8b` | not needed |
| 2 | `4811abd5`: TAP 10/20 (`37-17-red-sound-state.json`) and 3/8 (`37-17-red-sound-bar-page.json`), both RED_EVIDENCE_OK | `13b0c0c6` | not needed |
| 3 | `6e2b8ced`: TAP 23/70 (`37-17-red-pill-precedence.json`) and 3/11 (`37-17-red-pill-page.json`), both RED_EVIDENCE_OK | `54af268d` | not needed |

**Semantic assessment (RED).** In every record the named target ran and failed on its planned assertion:

- `chimeCount() >= 3` received 0;
- a DRAFT->PENDING frame built one AudioContext;
- the sound state stayed "on" after the browser suspended the context;
- there was no `kds-sound-bar` on a muted board;
- the pill read "Live" with sound off.

Each RED commit carried a source interface stub, as 37-19 did, so the suites loaded and failed on assertions rather than on imports.

**Green on the RED tree, and the arm that proves each one can fail:**

| Test | Why it was green | Break arm |
|------|------------------|-----------|
| 5000 ms constant | it was in the stub | — |
| no context without a tap | — | B |
| policy-absent "running is on" and "kept suspended is off" (state fallback) | Task 1 already read the context's state | H2 |
| the flash while muted | 37-16's ring never read the mute | I |
| pill rows 1, 4, 5, 6 and 10 | the four-state pill already rendered them | K |

**Count correction.** The Task 2 RED commit message and the first `actual` field said "10/22". The report itself always said `1..20`, with 10 not ok. The evidence JSON now says 10/20 and records the correction, and the classifier was re-run: RED_EVIDENCE_OK.

## Verification

**Tracer `<verify>`, re-run after commit.** `npm --prefix frontend test -- components/dashboard/kitchen/__tests__/kds-sound.test.tsx` passed 8 tests, rc 0. ⚡ Tracer verified end-to-end, then expanded.

**Acceptance criteria, both directions.** Every arm was run on the committed state. Each restore was verified by sha256 against a scratch backup, with a clean run afterwards and a clean `git status`.

| Criterion | Before / break direction | After / real tree |
|-----------|--------------------------|-------------------|
| `rg -uu -n 'new AudioContext' page.tsx` | rc 0, 1 hit (`114: const ctx = new AudioContext()`) | rc 1, no output |
| Ring-once fail arm (A, interval removed) | 3 red: ≥3 chimes, rings again, page rings | 12/12 green |
| F2: no AudioContext before the tap | arm B (context built at mount): 4 red | — |
| F2: zero starts after Accept | arm C (pending gate removed): 4 red | — |
| Shop switch silences | arm D (count every shop): 1 red | — |
| F4: muted bar and control visible on load | arm E (muted bar hidden): 5 red, including F4 | 28/28 green |
| Suspended later reads off | arm F (no statechange listener): 2 red | — |
| getAutoplayPolicy: both branches | arm G (policy ignored): 2 red; arm H2 (state ignored): 3 red, including "absent: kept suspended is off" | — |
| Bar not dismissible | arm J (a × button added): 2 red | — |
| Flash runs while muted | arm I (`kds-new-flash` removed): 1 red | — |
| F3 table | arm K (precedence reverted so live wins): 22 red; arm L (Sound off and Muted swapped): 2 red | 81/81 green |
| Constant width | arm M (sizers dropped): 1 red (jsdom). In the browser: 230/234/236/240 px without sizers, 240 px in all ten states with them | — |

**The plan's `<verification>`.** The kds-sound, kds-feed-status and feed-state suites are green, with the F2, F3 and F4 fail arms recorded above.

The build is green and type-checks. A deliberate type error was introduced (`"alive"` assigned to `FeedPillStatus`) and the build failed with TS2322, rc 1. After the restore, a clean build passed with rc 0. The built CSS contains `.after\:content-\[attr\(data-pill-sizer\)\]:after{--tw-content:attr(data-pill-sizer);content:var(--tw-content)}`.

**Full frontend suites, final tree.**

- **jest:** 2472 passed in 210 suites. Baseline after 37-16 was 2408 in 208. The +64 are 20 kds-sound, 11 kitchen-alert, 15 feed-state and 18 kds-feed-status tests; page.test stays at 32. `docs/metrics.json` drift belongs to the sub-theme gate plan.
- **lint:** 0 errors, 31 warnings, unchanged from the baseline.
- **build:** rc 0.

**Live run.** The stack was touched: the frontend only.

- **Build path.** `docker compose build frontend` produced image `7e5a7401`. Core-java's tag was measured before and after and is unchanged (`94e8e7b2`, 10:30:05Z). The frontend was recreated with `up -d --no-deps --force-recreate frontend` and was healthy in 12 s.
- **Freshness.** `check-runtime-freshness.sh` reports frontend FRESH (tagged 13:20:02Z, at or after `54af268d`). Core-java still shows DRIFT, which is pre-existing (WINDOWS #30: V80 and the customer gate). It was not touched, so the gate exits 1 for core-java alone.
- **Bundle.** The running bundle contains the bar copy, `data-pill-sizer` and the title string.
- **e2e.** `kitchen-flow.spec.ts` passed 18/18 on mobile and desktop, logged in through real SSO as admin-user. The pre-change spec run against the same runtime fails 2 tests: `toHaveCount(1)` on the old mute button, and "Live" against a pill that read "Sound offLast updated 14:22:01".
- **Screenshots.** Six are in `evidence/37-17-screens`: 390 px and 1280 px, each with sound off, sound on and muted. Live reads at both widths:
  - pill width 240 px in every state;
  - control height 44 px;
  - no horizontal overflow;
  - title "(20) New orders · Kitchen Display — J'Toye".

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The existing page tests and `e2e/kitchen-flow.spec.ts` asserted the icon-only mute button and an unconditional "Live"**

- **Found during:** Tasks 1 and 3.
- **Issue:** The plan replaces that button and makes "Live" conditional on sound. `page.test.tsx` (three mute tests and the pill test) and the e2e spec (the #593 mute block and the #106 online assertion) would fail against a correct page.
- **Fix:** Each now drives the new control.
  - Tap "Turn on sound", then Mute alerts, then Unmute alerts.
  - The `kds-muted` assertions are unchanged.
  - "Live" is asserted after the tap.
  - The e2e locator is the live-tree testid, because the bar carries a second "Turn on sound" button by design.
- **Verification:** jest is green, and the e2e passes 18/18 live. The pre-change spec fails against the new runtime, which shows the edit was needed.
- **Files:** `frontend/app/dashboard/kitchen/__tests__/page.test.tsx`, `frontend/e2e/kitchen-flow.spec.ts`.
- **Commits:** `6270cd8b`, `54af268d`.

**2. [Structure] Page-level tests in a new file and a shared fake**

- **Issue:** The plan's file list names only `kds-sound.test.tsx` for Tasks 1 and 2. The wiring needs the real page: the shop switch, the socket frame, the title and F4.
- **Fix:** Those tests live in `app/dashboard/kitchen/__tests__/kitchen-alert.test.tsx`. The recording AudioContext is `frontend/test-utils/fake-audio-context.ts`, beside the existing test-utils, and is used by three suites.
- **Commits:** `dad7525d`, `4811abd5`, `6e2b8ced`.

**3. [Rule 2 - Correctness] The chime counts only the selected shop's orders**

- **Issue:** After a shop switch, the order map holds the previous shop's orders until the new read lands. Counting `newOrders.length` would keep ringing for a board the operator has just left. Arm D measured this.
- **Fix:** The count filters on `shopId === selectedShopId`, and a test covers it. The tab title still counts the lane exactly as shown, so the title and the lane never disagree.
- **Commit:** `6270cd8b`.

**4. [Rule 2 - Correctness] "Unmute alerts" also unlocks audio**

- **Issue:** UI-SPEC gives the muted bar an "Unmute alerts" button. A muted board loaded without a context would otherwise need two taps to ring.
- **Fix:** The unmute handler is a gesture, so it calls `enableSound()` in the same tap. A test covers it.
- **Commit:** `13b0c0c6`.

**5. [Measurement] The pill width is fixed by construction, not by guessing a larger `min-w`**

- **Issue:** The plan asks for a `min-w` that fits "Not updating" and "Reconnecting". Measuring showed the old `min-w-[5.5rem]` already failed this: the pill was 230 to 240 px wide depending on its word.
- **Fix:** The label slot stacks a pseudo-element sizer per label. The width is 240 px in all ten states, in the real font and the built CSS.
- **Commit:** `54af268d`.

**Total deviations:** 5. Of these, 1 is Rule 3, 2 are Rule 2, and 2 are structural or measurement changes. **Impact:** all are within the kitchen surface. None widens the scope or changes an API.

## Issues Encountered

- **The dev tenant's 20 stale PENDING orders now ring every 5 s once sound is on.** The live tab reads "(20) New orders". This is D-12 working. It is recorded in `deferred-items.md`: reject the seed probes before any audio UAT.
- **The labelled control takes its own row in the header** at 390 px, and at 1280 px beside a long shop name. It is there from the first paint, so it causes no CLS. Logged in `deferred-items.md` for the UAT design look.
- **Persisted mute and SSR.** This is the pre-existing `useState`-initialiser pattern: the server renders the off bar, and the client renders the muted bar. Both are the same box. Not measured; logged.

## Known Stubs

- `frontend/app/dashboard/kitchen/page.tsx:612` passes `stopped: false`, and the feed state gets no `signedOut` or `noAccess`. The K2 board-stopped overlay, the session lapse and the access-ended event are 37-18's to wire. The hook and the pill already honour all three under test. Recorded as **WINDOWS #31** (stub, open), to close when 37-18 wires them.

## Threat Flags

None. There is no new endpoint, auth path, storage key or schema change.

- **T-37-44 (Repudiation: "Live" or sound claimed while not live) is mitigated.** It is covered by the precedence table and its arms K, L and M, and by the non-dismissible bar with arm J.
- **T-37-45 (an alert loop after accept) is mitigated.** The loop is gated on the pending count. Arm C proves the test sees a loop that ignores it.
- **No package was installed (T-37-SC).**

## User Setup Required

None.

## Next Phase Readiness

- **For 37-18 (K2 board stopped):**
  - Pass the lapse and access flags as `signedOut` and `noAccess` into `deriveFeedState`, and `stopped` into `useKitchenAlert`, then close WINDOWS #31.
  - The alarm must reuse the same context. The hook keeps it in `ctxRef`, so expose it or extend the hook: three 440 Hz square tones every 3 s.
  - The title for this state is `STOPPED · Kitchen Display — J'Toye`.
- **For 37-23 (sub-theme gate):**
  - The frontend runtime is fresh at `54af268d`, and `kitchen-flow` is green live.
  - Core-java still predates V80 (WINDOWS #30).
  - The audible check in a noisy room, and the stale-order wall, are the human rows.
- **Requirements:** RWO-020 and RWO-021 are also declared by 37-23, so `requirements.ready-ids` decides. Neither is marked complete until 37-23 lands.

## Self-Check: PASSED

- FOUND: every `key-files.created` path. Also found: `evidence/37-17-red-{repeating-chime,page-alert,sound-state,sound-bar-page,pill-precedence,pill-page}.json`, `evidence/37-17-browser-probe.json`, `evidence/37-17-e2e-kitchen-flow{,-prechange-spec}.txt` and `evidence/37-17-screens/` (6 JPEGs).
- FOUND, as ancestors of HEAD: `dad7525d`, `6270cd8b`, `4811abd5`, `13b0c0c6`, `6e2b8ced`, `54af268d`.
- MEASURED: `git rev-list --count f3cf9f0a..54af268d` = 6. `gsd_run check evaluation-scope --plan 37-17 --commits-only` resolved the plan's commits, rc 0.
