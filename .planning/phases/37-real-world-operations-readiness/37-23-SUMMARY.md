---
phase: 37-real-world-operations-readiness
plan: 23
subsystem: gate
tags: [runtime-parity, compose, kitchen, kds, chime, alarm, pause, opening-hours, playwright, pr-cut, d-03, d-04, uxt-011, uxt-020, uxt-021, uxt-022]

requires:
  - phase: 37-15
    provides: "the sub-theme gate shape (Part 1 static gates and suites, Part 2 RED/rebuild/GREEN/live/goods/screens/branch pointer) and the PR-cut procedure"
  - phase: 37-16
    provides: "the New lane, Accept/Reject, notes and fulfilment on the card, KITCHEN_STATUSES parity, the kds-new-flash ring"
  - phase: 37-17
    provides: "useKitchenAlert, the sound control and bar, the tab title, the ten-state pill"
  - phase: 37-18
    provides: "KdsBoardStopped (K2), the alarm, the session keep-alive, e2e/kitchen-session-lapse.spec.ts"
  - phase: 37-19
    provides: "V80 opening schedule, hours_source and the pause columns"
  - phase: 37-20
    provides: "ShopAvailabilityPolicy, the pause endpoints, the 409 shop-closed, the public availability fields"
  - phase: 37-21
    provides: "PauseOrdersControl, PausedBanner, OpeningScheduleEditor, HoursNoticeCard and the shops-table badges"
  - phase: 37-22
    provides: "the storefront availability reader, checkout gating, JSON-LD hours, the seeder's all-day week"
provides:
  - "D-03/D-04 reconciliation: PR 913 (37-B) MERGED by PR state, its final reviewed head and origin/main merged into phase-37-ops-readiness (fbf3eee1, d490d15c)"
  - "Every static gate rc=0 and the full suites green on the 37-A head (core-java 1683 unit + 1084 integration, jest 2622/222, build); metrics at V80, 5471 logical invocations"
  - "The F23 flash-rate arm in e2e/kitchen-flow.spec.ts (computed animation 1 s, none under reduced motion) and a countable 37-17 jest table"
  - "37-A proven on the delivered compose runtime: every image rebuilt and recreated, freshness PASS 4/4 (0 unverified), OpenAPI PASS, strict scoping ON, V80 the head, next 16.3.8 and x/net 0.60.0 read out of the running artifacts"
  - "Live kitchen proofs: a storefront order in the New lane 4.3 s after the POST with its note, badge and allergen chip; the chime every 5 s until Accept and silent after; mute shown; pause -> storefront Paused, guest order 409 shop-closed in words, resume -> 201; the board-stopped alarm at 3 s capped at 120 s; MCP create_order still DRAFT; seeded schedule open; a legacy fixture open with the vendor notice; 38-minute stay-live"
  - "Local PR branch phase-37-a-kitchen (05b9ea01), one squash commit on origin/main, tree equal to tag cut/phase-37-a-kitchen (6a825865); not pushed"
affects: [gsd-ship-37-A, 37-24, 37-C, 37-E, 37-42, phase-37-uat]

actuals:
  tokens: 48642
  tasks: 3
  commits: 15
plan_head_before: 4f478bd86da2bade859420b990f212738962a0fd
plan_head_after: a28f393c4448de191f9f1c79b013a95b4cad13a7

tech-stack:
  added: []
  patterns:
    - "A sound proof without ears: instrument OscillatorNode.start from an init script and read cadence, frequency and wave type; audibility stays a human row"
    - "A squash-merged PR branch is reconciled by applying the PR's post-cut diff onto the integration tree (merge -s ours, then git apply -3 of the diff), never by a three-way merge of the squash"
    - "A runtime that already carries the code under test is recorded as such; the RED on record is the freshness gate and the content identities, with the product RED cited from the plan that measured it"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-23-gate-37A.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-23-screens/
  modified:
    - frontend/e2e/kitchen-flow.spec.ts
    - frontend/components/dashboard/kitchen/__tests__/kds-feed-status.test.tsx
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - .planning/WINDOWS.md

key-decisions:
  - "37-23 Task 2: proceed — 37-A may take the shared compose stack (owner, 2026-10-11)"
  - "37-23: the pre-rebuild runtime (261010-ppb, 75bb3211) already carried all of 37-A, so the two specs were green before the rebuild; that reading is recorded as it was, the RED on this runtime being the freshness gate (3 of 4 image-not-rebuilt) and the version identities, and the product RED cited from 37-16's pre-fix run and the Task 1 F23 CSS break arms — a green run is never relabelled RED"
  - "37-23: Peckham's 17 stale PENDING probe orders (2026-08-31 .. 2026-10-03 QA and persona probes) were rejected through the kitchen's own cancel path so the chime could be shown to stop with Accept; rows kept, status CANCELLED"
  - "37-23: the legacy-always-open proof used a database fixture on one demo shop (hours_source LEGACY_ALWAYS_OPEN, no schedule — the state V80 gives an hours-less shop) restored by content (md5 equal before and after), because the seeder declares every shop and only V80 writes that value"
  - "37-23: the owner opens the 37-A PR by hand in the ship shape (push first, review second); /gsd-ship cannot open a sub-theme PR mid-phase (verification.status missing), which supersedes the plan's /gsd-ship sentence"
  - "37-23: the PR branch carries no upstream (origin/main tracking unset after the cut) so a bare push cannot target main"

patterns-established:
  - "Instrument defects are corrected and the probe re-run before any reading is taken as evidence; the first reading is recorded beside the second (shop id resolution, the idempotency-key mismatch, the NaN burst counter)"

requirements-completed: [RWO-011, RWO-020, RWO-021, RWO-022]

coverage:
  - id: G1
    description: "D-03/D-04 reconciliation before any gate: PR 913 MERGED by PR state, the final reviewed head and origin/main merged into phase-37-ops-readiness with every review-fix hunk proven present"
    verification:
      - kind: other
        ref: "evidence Part 1.0–1.1: gh pr view state MERGED; merges fbf3eee1 and d490d15c; reverse git apply --check of the PR's post-cut diff rc=0 on the merged index and non-zero on a HEAD-only index"
        status: pass
    human_judgment: false
  - id: G2
    description: "Every static gate green on the 37-A head and the full core-java, jest and build suites green"
    verification:
      - kind: other
        ref: "evidence 1.2–1.5: docs-freshness RED then --write then GREEN; check-doc-metrics 25 FAIL then PASS 37/37; nine more gates rc=0 with six break arms; core-java 1683/0 unit + 1084/0 integration; jest 2622/222; build rc=0 with a TS2322 break arm"
        status: pass
    human_judgment: false
  - id: G3
    description: "The F23 flash-rate arm in kitchen-flow.spec.ts, falsified against the CSS before the stack was touched"
    requirement: "RWO-020"
    verification:
      - kind: e2e
        ref: "frontend/e2e/kitchen-flow.spec.ts#the New card's ring flashes no faster than once per second, and not at all under reduced motion: 200 ms -> FAIL, reduced-motion rule removed -> FAIL (evidence 1.3); live PASS on both projects (2.5) and computed 1s on the live card (2.6 A)"
        status: pass
    human_judgment: false
  - id: G4
    description: "Owner decision recorded before any stack action"
    verification:
      - kind: other
        ref: "Task 2 blocking-human checkpoint answered 'proceed' (2026-10-11); nothing on the stack was written before it"
        status: pass
    human_judgment: false
  - id: G5
    description: "Runtime parity: pre-rebuild identity recorded, every image rebuilt and recreated, check-runtime-freshness 0 unverified, image id = tag id, next/x-net/OrderService.class read out of the running artifacts, V80 the head, OpenAPI live PASS"
    requirement: "RWO-011"
    verification:
      - kind: other
        ref: "evidence 2.1, 2.3, 2.4: freshness rc=1 before (core-java, edge-go, frontend image-not-rebuilt) and rc=0 after; mode B caught on mcp-server and cleared by --force-recreate; next 16.3.7 -> 16.3.8; x/net 0.58.0 -> 0.60.0; OrderService.class 28921 -> 29439 bytes; OpenAPI PASS; strict scoping ON"
        status: pass
    human_judgment: false
  - id: G6
    description: "kitchen-flow and kitchen-session-lapse run on the pre-rebuild runtime (recorded as found: 30 passed, the images already held 37-A) and GREEN on the rebuilt runtime with the regression specs"
    requirement: "RWO-020"
    verification:
      - kind: e2e
        ref: "evidence 2.2 (30 passed pre-rebuild, with the reason) and 2.5 (the plan's five specs 98 passed, 0 skipped; public-a11y 40 passed)"
        status: pass
    human_judgment: false
  - id: G7
    description: "A storefront order reaches the New lane within seconds with its note, fulfilment badge and allergen chip; the chime repeats every 5 s until Accept and stops with it; Accept confirms, the bump is big and fast; mute is shown"
    requirement: "RWO-011"
    verification:
      - kind: e2e
        ref: "evidence 2.6 A: card 4298 ms after the 201; note, DELIVERY badge, 1 chip for mask 8; 2 chimes 5000 ms apart with a 0-chime control; Accept -> /confirm 200, 0 oscillator starts after; bump 44x164 px -> 200 in 69 ms; Muted bar and pill"
        status: pass
    human_judgment: false
  - id: G8
    description: "Pausing from the kitchen makes the storefront show Paused and a guest order return 409 shop-closed in human words; resuming restores ordering"
    requirement: "RWO-022"
    verification:
      - kind: e2e
        ref: "evidence 2.6 B: PUT /pause 200; banner and storefront notice at the server's UK time; public openNow false; guest order 409 shop-closed PAUSED 'Peckham Jollof Co. isn't taking orders (paused). Reopens at 19:19.'; DELETE 200; guest order 201"
        status: pass
    human_judgment: false
  - id: G9
    description: "A lapsed session stops the board loudly: overlay, unchanged URL, STOPPED title, Signed out pill, the 440 Hz square alarm every 3 s, Silence alarm, and the 120 s self-cap with the overlay kept"
    requirement: "RWO-021"
    verification:
      - kind: e2e
        ref: "evidence 2.6 C and 2.8 addendum: 3 bursts 3000 ms apart then 0 after Silence; 41 bursts 0..120 s and none after, overlay still up; kitchen-session-lapse 10/10 live (2.5)"
        status: pass
    human_judgment: false
  - id: G10
    description: "MCP create_order still produces a DRAFT that never reaches the New lane (the expected pre-37-E behaviour, D-13 pending 37-42)"
    verification:
      - kind: integration
        ref: "evidence 2.6 D: tools/call create_order HTTP 200, order status DRAFT placedVia VENDOR, mcp log status 201, 0 matches on the kitchen read, DELETE 204"
        status: pass
    human_judgment: false
  - id: G11
    description: "A demo shop with the seeded all-day schedule is open; a legacy-always-open shop (fixture) is open and its vendor is told"
    requirement: "RWO-022"
    verification:
      - kind: e2e
        ref: "evidence 2.6 E and F: Peckham openNow true with seven 00:00-00:00 days; Brixton as LEGACY_ALWAYS_OPEN: public legacyAlwaysOpen true, guest order 201, dashboard hours-notice-card at 390/768/1280, 'Hours not set' badge, storefront Open now; restored by md5"
        status: pass
    human_judgment: false
  - id: G12
    description: "The 37-A goods hold on the rebuilt runtime: P2-FUN-17, P2-FUN-18, P2-FUN-20, P2-TUN-13, P2-TUN-14, P2-CHA-15, P2-TUN-15, P2-REG-15, P2-CHA-14, P2-CHA-18, P2-KEM-22"
    verification:
      - kind: e2e
        ref: "evidence 2.7 and 2.8: 38 ticks Live with no overlay, a ticket 1287 ms after the post-wait order, accepted and bumped; the refusal wording in 2.6 G; kitchen without shopId 400"
        status: pass
    human_judgment: false
  - id: G13
    description: "PR branch phase-37-a-kitchen cut fresh from origin/main as one squash commit immediately after check-branch-behind-base passed, tree equal to cut/phase-37-a-kitchen, not pushed; origin/main merged back (no-op)"
    verification:
      - kind: other
        ref: "evidence 2.12: plan verify line rc=0 (behind-base PASS 222 ahead 0 behind; trees equal; rev-list count 1 = 05b9ea01); fail direction tree-vs-main rc=1; ls-remote 0 heads; message 0 attribution lines"
        status: pass
    human_judgment: false
  - id: H1
    description: "The new-order chime is clearly audible in a noisy kitchen and repeats until Accept (VALIDATION manual row)"
    requirement: "RWO-020"
    verification: []
    human_judgment: true
    rationale: "Cadence (5 s, silent after Accept) is measured in evidence 2.6 A; loudness over a Friday service is a person's call. workflow.human_verify_mode is end-of-phase, so it is harvested into the phase UAT with the steps in evidence 2.11."
  - id: H2
    description: "The board-stopped alarm is distinguishable by ear from the chime, and the timings are signed off: 5 s chime, 3 s alarm, 120 s alarm cap (UI-SPEC open item 1)"
    requirement: "RWO-021"
    verification: []
    human_judgment: true
    rationale: "Measured: 440 Hz square bursts every 3 s capped at 120 s against a sine chime every 5 s; whether the two read as different alarms by ear, and whether the intervals suit a kitchen, are the owner's (evidence 2.11 items 2 and 3)."
  - id: H3
    description: "Screen review at 390/768/1280: fresh board (sound off), New lane with a pending card, muted, paused banner, board stopped, the storefront paused notice, the legacy vendor notice"
    verification: []
    human_judgment: true
    rationale: "Visual judgement is a person's; 21 JPEGs are in evidence/37-23-screens/. The 1 Hz ring flash cannot be judged from a screenshot (its period is asserted by the F23 arm)."

duration: 95 min
completed: 2026-10-11
status: complete
---

# Phase 37 Plan 23: Sub-theme 37-A gate — runtime proof and PR cut Summary

**37-A is proven on the rebuilt compose runtime. Every image was rebuilt and recreated, the freshness gate passes with 0 unverified, and the running artifacts carry next 16.3.8, x/net 0.60.0 and the #913 review fixes. On the live board a storefront order lands in the New lane in 4.3 s, chimes every 5 s until Accept and goes quiet with it; a kitchen pause makes the storefront say Paused and a guest order get a worded 409, and resuming restores the 201; a lapsed session stops the board with a 440 Hz alarm that caps itself at two minutes with the red screen kept. The pre-rebuild runtime already held 37-A, so the specs were green before the rebuild and that is recorded as found. The PR branch `phase-37-a-kitchen` is cut as one squash commit (`05b9ea01`).**

## Performance

- **Duration:** about 95 minutes over two sessions. Task 1 ran from 14:03Z (the 30-minute gradle run inside it); Task 3 ran 17:46Z to 18:39Z.
- **Started:** 2026-10-11T14:03Z
- **Completed:** 2026-10-11
- **Tasks:** 3 (Task 2 was the owner's decision)
- **Files modified:** 31 by this plan's own commits (8 text files, 21 screenshots, the evidence file, this summary)

## Accomplishments
- **Reconciliation (D-03, D-04).** PR 913 read MERGED by PR state; its final reviewed head was folded in as the post-cut diff on top of our tree (a three-way merge of the squash would have resurrected cut-time lines), then origin/main. Every review-fix hunk was proven present by a reverse `git apply --check`, with a HEAD-only index as the fail arm.
- **Static gates and suites (Task 1).** docs-freshness VOIDed on a 37-17 jest table, which was made countable; then RED, `--write`, GREEN; the prose half went from 25 FAIL to 37/37; eight more gates rc=0 with six break arms. core-java 1683/0 + 1084/0, jest 2622 in 222 suites, build green with a type break arm. The F23 arm was added and falsified against the CSS before the stack was touched.
- **RED recorded as found.** The 261010-ppb images already carried all of 37-A, so kitchen-flow and kitchen-session-lapse were 30 passed before the rebuild. What was red on that runtime: the freshness gate (3 of 4 images behind the #913/#914 merges), next 16.3.7 and x/net 0.58.0 in the running artifacts.
- **Rebuild and parity.** gradle, `npm ci` and `go build` executed (mcp-server a full cache hit); containers recreated; the gate caught mcp-server's mode-B retag and `--force-recreate` cleared it; freshness PASS 4/4, OpenAPI PASS, strict scoping ON, V80 the head; `OrderService.class` grew 28921 → 29439 bytes.
- **GREEN.** The plan's five specs: 98 passed, 0 skipped; public-a11y 40 passed.
- **Live proofs.** Lane arrival, note, badge and allergen chip; the chime cadence with a silent control and a silent after-Accept window; the bump's size and speed; mute shown; pause → Paused → 409 in words → resume → 201; the lapse overlay and alarm, Silence, and the 120 s cap; MCP `create_order` DRAFT noted; the seeded schedule open; a legacy fixture open with the vendor notice, restored by md5; a 38-minute stay-live with a ticket accepted and bumped afterwards.
- **Screens** at 390/768/1280 (21 JPEGs) and the "for the owner's ear and eye" list for the phase UAT.
- **PR cut.** `cut/phase-37-a-kitchen` = `6a825865`; `phase-37-a-kitchen` = `05b9ea01`, one commit above origin/main, trees equal, upstream unset, not pushed; D-04 merge back a no-op.

## Task Commits

1. **Task 1: reconciliation, static gates and full suites** — `fbf3eee1` (merge, D-03), `d490d15c` (merge, D-04), `7068e640` (test: F23 arm, countable table, metrics at V80), `93b23cec` (docs: Part 1 evidence)
2. **Task 2: checkpoint:decision, blocking-human.** The owner answered "proceed". No commit.
3. **Task 3: RED, rebuild, GREEN, live proofs, goods, screens, the branch pointer** — `6a825865` (docs: Part 2 evidence, 21 screens, WINDOWS #38 fixed), `a28f393c` (docs: the 2.12 branch pointer, after the cut)
   - PR branch `phase-37-a-kitchen` at `05b9ea01` (a squash of `cut/phase-37-a-kitchen` = `6a825865`; local only, not pushed)

Between Task 1 and Task 3 the orchestrator committed `fa717294` (HANDOFF delta, STATE stamp) and `0da2fa9c` (gitleaks allowlist); both are in the squash. `commits: 15` is `git rev-list --count 4f478bd8..a28f393c`: the 6 commits above, the 2 orchestrator commits, and the 7 commits the two merges brought in (`36d7f74b`, `2543974f`, `63309848`, `b3b87a47`, `210b176c`, `aec7e0a7`, `7364b215`).

**Plan metadata:** this SUMMARY goes in the docs commit that follows. STATE.md, ROADMAP.md and REQUIREMENTS.md are the orchestrator's (RWO-022 was left blocked for 37-21 because this plan also declares it; 37-23 is the last declaring plan).

## Files Created/Modified
- `evidence/37-23-gate-37A.md`: Part 1 (1.0–1.6) and Part 2 (2.1–2.13)
- `evidence/37-23-screens/`: 21 JPEGs at 390, 768 and 1280
- `frontend/e2e/kitchen-flow.spec.ts`: the F23 computed-style arm; the New-lane test also asserts the note and badge
- `frontend/components/dashboard/kitchen/__tests__/kds-feed-status.test.tsx`: the not-Live rows as a countable array literal, pinned to the filter it replaces
- `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`: 5471 logical invocations, schema head V80
- `.planning/WINDOWS.md`: #38 fixed

## Decisions Made
See `key-decisions`. The one with consequences beyond this plan: the chime-until-Accept proof needed an empty New lane, so Peckham's 17 stale probe orders were rejected through the kitchen's own cancel path (rows kept, CANCELLED).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] docs-freshness VOIDed on a 37-17 jest table the block counter cannot read**
- **Found during:** Task 1
- **Issue:** `.each(TABLE.filter(...))` is not a resolvable array literal (rc=2), so the metrics could not be regenerated.
- **Fix:** the not-Live rows became an array literal by reference, with a test pinning it to the filter; 37 jest tests pass, a row-swap break arm reds two.
- **Files modified:** frontend/components/dashboard/kitchen/__tests__/kds-feed-status.test.tsx
- **Commit:** 7068e640

**2. [Rule 3 - Blocking] The residue allowlist pointed at a moved line**
- **Found during:** Task 1 gates
- **Fix:** `frontend/types/api.ts:366` → `:390`, reason unchanged; gate rc=1 → 0. Committed in 7068e640.

**3. [Rule 3 - Blocking] mcp-server's container kept the old image id after a cache-hit retag (mode B)**
- **Found during:** Task 3 rebuild
- **Fix:** `up -d --no-deps --force-recreate mcp-server`; freshness rc=1 → 0. Runtime only, no commit.

**4. [Rule 3 - Blocking] The order-metric counter reset with core-java**
- **Fix:** `scripts/seed-order-metric.sh` (one guest order on Brixton, 201); check-alert-metrics rc=1 → 0. Runtime only.

### Plan wording that did not hold (recorded, not relabelled)
- **"RED on the pre-rebuild runtime."** The runtime had been rebuilt by 37-22 and 261010-ppb after every 37-A commit, so the two specs were green before the rebuild. Recorded as found (evidence 2.2) with what was red instead and where the product RED lives.
- **"The PR is opened by the owner via /gsd-ship."** Superseded: the orchestrator pushes and opens the PR by hand in the ship shape (evidence 2.12).

### Instrument defects in my own probes (fixed before any reading was used)
- the public shop DTO carries no `id` (resolved through the vendor's shop list);
- the first wording probes sent a body `idempotencyKey` that differed from the header (every arm answered the mismatch 400; re-run with one key);
- the first alarm-cap counter compared a number's `.t` (NaN) and counted 1 burst, which also made its "none after the cap" arm vacuous; fixed and repeated (41 bursts, none after 120 s).

**Total deviations:** 4 auto-fixed (all blocking), 2 plan-wording records, 3 instrument fixes. **Impact:** no product code changed in Task 3; the 37-A tree under review is the one Task 1 proved.

## Issues Encountered
- `docker compose up -d --wait` exits 1 when the two one-shot containers (`keycloak-realm-render`, `ollama-init`) exit 0; every long-running service was `Up (healthy)`. Read `compose ps -a`, not the exit code.
- With 17 stale PENDING orders in the lane the chime rings regardless; the "until Accept" half cannot be shown on a lane that is never empty.

## Known Stubs
None introduced.

## Ledger
- WINDOWS #38 (alarm cap) marked fixed by the live reading; its by-ear half is H2.
- Still open and partly discharged here, not marked: #34 (the alarm cadence, Silence alarm, the silent refresh over seven real token lifetimes and the lapse spec re-run are now live; a REAL grant revoke ending the board was not provoked — admin-user is a realm admin); #35 (the checkout race UI and the NEEDS_REVIEW JSON-LD rule remain jest-only); #37 (the pause 403 as a revoked STAFF user, the double tap and a hand-provoked validation error remain for the UAT).

## Threat Flags
None new. T-37-58 (specs green against a stale runtime) is mitigated by 2.1–2.5; T-37-SC (no package installed) held — the host `frontend/node_modules/next` is still 16.3.6 and the images ran `npm ci` from the lockfile.

## User Setup Required
None for the dev stack.

## Next Phase Readiness
- **The orchestrator** pushes `phase-37-a-kitchen` (`05b9ea01`) and opens the 37-A PR by hand in the ship shape; review fixes, merges from main and the CHANGELOG entry citing the PR number land on that branch.
- **D-04 hand-off:** origin/main (`7364b215`) had nothing new at the cut, so the merge into `phase-37-ops-readiness` was a no-op; 37-24 starts on current main.
- **Phase UAT:** H1 (chime audibility and repeat), H2 (alarm by ear, the 5 s / 3 s / 120 s sign-off), H3 (screens).
- **Requirements:** RWO-011, RWO-020, RWO-021 and RWO-022 are ready to mark (this is the last plan declaring RWO-022).

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-11*

## Self-Check: PASSED

- FOUND: evidence/37-23-gate-37A.md (contains "phase-37-a-kitchen", "check-runtime-freshness" and "LastTagTime"), evidence/37-23-screens/ (21 JPEGs), frontend/e2e/kitchen-flow.spec.ts (the F23 test)
- FOUND commits (ancestors of HEAD): fbf3eee1, d490d15c, 7068e640, 93b23cec, 6a825865, a28f393c; `git rev-list --count 4f478bd8..a28f393c` = 15
- FOUND: phase-37-a-kitchen 05b9ea01, one commit above origin/main, tree equal to cut/phase-37-a-kitchen
