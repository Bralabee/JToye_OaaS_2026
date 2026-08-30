---
phase: quick-260830-hjs
plan: 01
subsystem: e2e-testing
tags: [playwright, csp, gsap, marketing, determinism]
requires: []
provides:
  - "data-motion-decided='scene|static' inert DOM marker on both marketing motion scopes"
  - "marketing-motion.spec.ts + csp-no-violations.spec.ts free of network-idle waits"
affects: [e2e-suite specDigest]
tech-stack:
  added: []
  patterns: ["deterministic predicate waits over idle heuristics", "both-branches decision stamp for absence assertions"]
key-files:
  created: []
  modified:
    - frontend/components/marketing/hero-scene.tsx
    - frontend/components/marketing/operator-entrance-scene.tsx
    - frontend/e2e/marketing-motion.spec.ts
    - frontend/e2e/csp-no-violations.spec.ts
decisions:
  - "Marker over pure-spec fix: no existing both-branches signal exists (data-motion-active is desktop-branch-only; the negative branch did nothing observable), so absence assertions had no deterministic anchor without it"
  - "ARM C vector corrected: a createElement'd inline script is ALLOWED by 'strict-dynamic' by design (non-parser-inserted trust propagation) — the arm as planned is structurally vacuous against this CSP; an inline event handler (needs 'unsafe-inline') is the genuinely-forbidden vector"
metrics:
  duration: "~9 min (11:45:59Z–11:54:34Z, 2026-08-30)"
  completed: "2026-08-30"
---

# Quick Task 260830-hjs: Fix Flaky networkidle Waits in Marketing Specs Summary

All 7 network-idle waits in marketing-motion + csp-no-violations replaced with predicate waits anchored to a new inert both-branches `data-motion-decided` stamp (mobile/reduced) and load-state + h1 render anchors (CSP), with all three break arms observed failing before the 18/18 clean pass (#687).

## Commits

| Task | Commit | Files |
| ---- | ------ | ----- |
| 1 — inert marker | `dfe4d71f` | hero-scene.tsx, operator-entrance-scene.tsx |
| 2 — 7 waits replaced | `356cdd87` | marketing-motion.spec.ts, csp-no-violations.spec.ts |
| 3 — arms + clean pass | (no code delta — arms reverted by design) | — |

## Marker-vs-pure-spec decision (recorded per plan)

NO existing both-branches signal exists — verified in source: `data-motion-active` is set only inside the `mm.add(DESKTOP_MOTION_QUERY, ...)` callback and the negative branch does nothing observable. An absence assertion therefore had nothing deterministic to anchor on: "no scene was built" was indistinguishable from "the enhancer has not run yet". A pure-spec fix would need a "hydration finished" DOM signal Next.js does not expose; any fixed timeout re-creates the vacuous-absence shape being removed. The marker is stamped in every branch ("scene" in the desktop callback, "static" after `mm.add` declined, "static" again in matchMedia cleanup so breakpoint flips stay truthful) and is inert: no stylesheet, no logic, no Tailwind class reads it. jsdom suites unaffected by construction (`canEnhance()` false under jest). `data-motion-decided` matched nothing in the tree pre-change (rg -uu rc=1), so the 2-file inertness result is a real delta.

## Task verifications

**Task 1:** both jsdom suites green (`Tests: 6 passed, 6 total`); inertness grep rc=0 listing exactly `components/marketing/hero-scene.tsx` + `operator-entrance-scene.tsx` (fail direction: rc=1 / zero files on the pre-change tree).

**Task 2:** `rg -uu -n 'networkidle'` over both specs → rc=1 (zero matches). Positive control on HEAD versions: 4 and 3 matches — pattern and scope proven able to match. `test(` block-count lines identical to HEAD (4 and 4) — docs/metrics.json untouched by construction. (First pass of the comment text contained the literal token and tripped the grep at rc=0; comments reworded to "network-idle heuristic" so the verification check kept full strength rather than being loosened.)

## Break arms — recorded fail-direction outputs (clean → arm → clean)

### ARM A — anchor is load-bearing (bogus locator)

Mobile-loop wait locator changed to `[data-motion-decided-bogus='static']`; `npx playwright test e2e/marketing-motion.spec.ts --grep "degrades"`:

```
ARM A rc=1
Error: expect(locator).toBeAttached() failed
  - Expect "toBeAttached" with timeout 15000ms
> 144 |  await expect(page.locator("[data-motion-decided-bogus='static']").first()).toBeAttached({
4 failed  (all 4 mobile-floor tests, both projects)
```

Instrument note: the first ARM A capture read `rc=0` because `$?` was taken after a `| tail` pipe (tail's status, not playwright's — no pipefail). Re-run with output redirected to a file and rc captured on the command's own line: rc=1. Both readings recorded; the corrected capture pattern was used for every subsequent run.

Restore proven by content: scoped `rg -uu 'bogus'` on both spec files rc=1; `git diff --exit-code` vs committed state rc=0.

### ARM B — absence assertion can fail (flipped expectation)

Mobile `.gsap-word` count flipped to `toBeGreaterThan(0)`; same run:

```
ARM B rc=1
Error: expect(received).toBeGreaterThan(expected)
Expected: > 0
Received:   0
> 149 |  expect(await page.locator(".gsap-word").count()).toBeGreaterThan(0)
```

Proves the assertion executes AFTER a settled static decision and reads real DOM — a genuinely-empty count, not a pre-hydration accident. Restore proven by content: `rg -uu -F '.gsap-word").count()).toBeGreaterThan'` rc=1 (the two surviving `toBeGreaterThan(0)` at lines 156/165 are pre-existing committed bounding-box checks, not arm residue); `git diff --exit-code` rc=0.

### ARM C — CSP window still catches violations (DEVIATION: vector corrected)

**As planned, the arm PASSED (vacuous):** injecting `createElement("script")` + `textContent` + `appendChild` produced `rc=0, 2 passed`. Root cause read from the served header: `script-src` carries `'strict-dynamic'`, whose defining behaviour is that **non-parser-inserted** script elements are trusted regardless of nonce — the planned vector is allowed *by the policy's design*, on any collection-window implementation. The arm passing was a true statement about the CSP, not evidence about the window.

**Corrected vector (per the plan's own rule — fix the arm, do not delete it):** an inline event handler (`div.setAttribute("onclick", ...)` + `.click()`), which requires `'unsafe-inline'` and is genuinely forbidden here:

```
ARM C (fixed vector) rc=1
Error: CSP violations: Executing inline event handler violates the following
Content Security Policy directive 'script-src 'self' 'nonce-…' 'strict-dynamic'
https://js.stripe.com https://*.js.stripe.com'. … The action has been blocked.
> 82 |  expect(violations, `CSP violations: ${violations.join("\n")}`).toEqual([])
2 failed  (both projects; violation text listed in the assertion message)
```

Restore proven by content: `rg -uu 'arm-c|ARM-C-INJECTED'` on both spec files rc=1; `git diff --exit-code` rc=0.

## Clean pass (closing arm)

Frontend image rebuilt (`docker compose build frontend` + `up -d`, container healthy, GET / → 200) — the mobile anchors are self-proving against staleness: a stale image lacks `data-motion-decided`, so the new waits would time out rather than pass vacuously.

```
clean-pass rc=0
18 passed (32.5s)   — 0 skipped; 18 = 2 desktop-only (desktop project) + 4 mobile-floor + 4 reduced-motion + 8 CSP
```

Mobile absence tests now complete in ~800ms each; before this change they timed out at 60s on the idle heuristic.

## Deviations from Plan

**1. [Rule 1 — vacuous check] ARM C's planned injection vector cannot fire under 'strict-dynamic'**
- **Found during:** Task 3, ARM C
- **Issue:** the plan's inline-script injection (createElement + textContent + appendChild) is a *non-parser-inserted* script, which `'strict-dynamic'` allows by design — the arm passed while proving nothing about the window
- **Fix:** substituted an inline event handler, which this policy genuinely forbids; observed failing with the violation text in the assertion message
- **Files modified:** none in the final state (arm code is transient by design; threat T-quick-260830-02 mitigation — removal proven by content, rg rc=1)

**2. [Rule 1 — instrument] ARM A's first rc reading was the pipe's, not playwright's**
- **Found during:** Task 3, ARM A
- **Issue:** `rc=$?` after `cmd | tail` read tail's exit status (0) while the run itself failed
- **Fix:** re-ran with output redirected to a file and rc captured on the command's own line (rc=1); pattern used for all subsequent runs

**3. [Rule 1 — self-defeating check] Task 2 explanatory comments initially contained the literal token "networkidle"**
- **Fix:** comments reworded ("network-idle heuristic") so the zero-match verification grep kept full strength; the check itself was never loosened

## Skip-budget / specDigest consequence (logged, not papered over)

Changing these two spec files changes the suite's **specDigest**, so every stored report predating this branch is VOID for `scripts/check-e2e-skip-budget.sh` (rc=2, failing closed as designed). Any gate claim needs a fresh FULL-suite run — out of scope for this quick task. Note vs the plan text: the skip-budget **local state is currently 6/6 PASS** — the 7/6 state the plan references was fixed earlier today by a demo-tenant onboarding reset; issue **#686 remains open** for the CI-lane aspect only.

## Verification (plan-level)

- All 7 network-idle waits gone; every replacement is a predicate on page state (attribute attached / element visible / load event) — never an idle heuristic
- Absence assertions strictly stronger: anchored to `data-motion-decided='static'`, shown load-bearing (ARM A) and falsifiable (ARM B); value-not-presence asserted, so a wrongly-built scene fails the wait itself
- CSP window shown live (ARM C, corrected vector) with the fixed settles (2000/2000/3000ms) unchanged
- No test() block added/removed, no retries, playwright.config.ts untouched, /dashboard test untouched
- Both specs green (18/18, rc=0) against the freshly rebuilt compose frontend at :3000, both projects

## Self-Check: PASSED

Commits dfe4d71f + 356cdd87 exist on feature/e2e-networkidle-waits; all four modified source files and this SUMMARY present; frontend working tree clean (only the untracked .planning quick-task directory remains, left for the orchestrator's docs commit).
