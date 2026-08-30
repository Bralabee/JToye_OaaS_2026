---
phase: quick-260830-hjs
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/components/marketing/hero-scene.tsx
  - frontend/components/marketing/operator-entrance-scene.tsx
  - frontend/e2e/marketing-motion.spec.ts
  - frontend/e2e/csp-no-violations.spec.ts
autonomous: true
requirements:
  - "#687 — marketing-motion/csp networkidle flake (deterministic waits)"

must_haves:
  truths:
    - "Neither spec file contains waitForLoadState('networkidle') — all 7 uses replaced (4 in marketing-motion, 3 in csp-no-violations)"
    - "The mobile/reduced-motion ABSENCE assertions are anchored to a both-branches signal (data-motion-decided) that only exists AFTER the motion decision has run — and the anchor was shown to make the test FAIL when broken"
    - "The CSP violation-collection window still catches a real violation (demonstrated with an injected inline script -> test FAILS listing it)"
    - "Both spec files pass end-to-end against the rebuilt compose stack at :3000"
    - "No assertion weakened: fixed CSP settle timeouts (2000/2000/3000ms) unchanged; mobile 500ms settles kept; no retries; playwright.config.ts untouched"
  artifacts:
    - path: "frontend/components/marketing/hero-scene.tsx"
      provides: "data-motion-decided='scene|static' stamp on the scope (inert, both branches)"
      contains: "data-motion-decided"
    - path: "frontend/components/marketing/operator-entrance-scene.tsx"
      provides: "data-motion-decided='scene|static' stamp on the scope (inert, both branches)"
      contains: "data-motion-decided"
    - path: "frontend/e2e/marketing-motion.spec.ts"
      provides: "deterministic anchors replacing all 4 networkidle waits"
    - path: "frontend/e2e/csp-no-violations.spec.ts"
      provides: "load-state + positive-anchor waits replacing all 3 networkidle waits, settle windows preserved"
  key_links:
    - from: "frontend/e2e/marketing-motion.spec.ts (mobile + reduced-motion blocks)"
      to: "frontend/components/marketing/hero-scene.tsx + operator-entrance-scene.tsx"
      via: "locator on [data-motion-decided='static']"
      pattern: "data-motion-decided"
    - from: "frontend/e2e/marketing-motion.spec.ts (desktop block)"
      to: "existing [data-motion-active='desktop'] stamp"
      via: "toBeAttached auto-retrying wait"
      pattern: "data-motion-active"
---

<objective>
Replace all seven flaky `waitForLoadState("networkidle")` calls in
`frontend/e2e/marketing-motion.spec.ts` and `frontend/e2e/csp-no-violations.spec.ts`
with deterministic waits, without weakening any assertion.

Purpose: `networkidle` on `/` is structurally unreliable — measured this session:
Next.js RSC prefetches (`?_rsc=` requests aborted with net::ERR_ABORTED and re-issued
under new tokens) plus `/api/customer-auth/session` polling keep resetting
Playwright's 500ms idle window, timing tests out at 60s while the page is fully
rendered. Playwright's own docs discourage `networkidle`. Tracked as #687; pollutes
every full-suite run.

Output: two edited spec files + a tiny inert `data-motion-decided` marker in the two
motion enhancer modules. Recorded decision (weighed per the planning constraint):
NO existing both-branches signal exists — verified in source, `data-motion-active` is
set only inside the `mm.add(DESKTOP_MOTION_QUERY, ...)` callback and the negative
branch does nothing observable — so an absence assertion has nothing deterministic to
anchor on without the marker. A pure-spec fix for the negative direction would need a
"hydration finished" DOM signal Next.js does not expose; any fixed timeout re-creates
exactly the vacuous-absence shape being removed. The marker is therefore the
genuinely deterministic fix.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@frontend/e2e/marketing-motion.spec.ts
@frontend/e2e/csp-no-violations.spec.ts
@frontend/components/marketing/hero-scene.tsx
@frontend/components/marketing/operator-entrance-scene.tsx
@frontend/lib/gsap-gate.ts
</context>

<interfaces>
<!-- Verified facts the executor needs — no re-exploration required. -->

From frontend/components/marketing/hero-scene.tsx (and the identically-shaped
useOperatorEntranceScene in operator-entrance-scene.tsx):

```typescript
useGSAP(() => {
  if (!canEnhance()) return          // jsdom/SSR guard — false under jest (no matchMedia in jsdom)
  const root = scope.current
  if (!root) return
  const mm = gsap.matchMedia()
  mm.add(DESKTOP_MOTION_QUERY, () => {          // callback fires SYNCHRONOUSLY when the query matches
    root.setAttribute("data-motion-active", "desktop")
    // ... splitWords -> .gsap-word spans, tweens ...
    return () => { root.removeAttribute("data-motion-active") }  // runs on unmount AND breakpoint change
  })
}, { scope })
```

From frontend/lib/gsap-gate.ts:
```typescript
export const DESKTOP_MOTION_QUERY =
  "(min-width: 768px) and (prefers-reduced-motion: no-preference)"
```

Networkidle inventory (verified with rg -uu, rc=0):
- marketing-motion.spec.ts lines 43 (/ desktop), 74 (/for-operators desktop),
  124 (mobile loop: / and /for-operators), 161 (reduced-motion loop: both paths)
- csp-no-violations.spec.ts lines 65 (homepage), 85 (/for-operators), 96 (storefront).
  The /dashboard test already uses { waitUntil: "domcontentloaded" } — leave it alone.

jsdom test facts (verified): hero-scene.test.tsx and operator-entrance-scene.test.tsx
rely on canEnhance() being false under jsdom, and assert only
`not.toHaveAttribute("data-motion-active")` — a marker stamped INSIDE the useGSAP
callback after the canEnhance guard never runs under jest, so those tests are
unaffected by construction. `data-motion-decided` matches NOTHING in the tree today
(rg -uu rc=1) — no collision, and inertness is trivially true at introduction.
</interfaces>

<tasks>

<task type="auto">
  <name>Task 1: Stamp an inert both-branches data-motion-decided marker in the two motion enhancers</name>
  <files>frontend/components/marketing/hero-scene.tsx, frontend/components/marketing/operator-entrance-scene.tsx</files>
  <action>
    In BOTH modules, inside the useGSAP callback (after the existing canEnhance/root
    guards), add the marker so it is truthful in every branch and across breakpoint
    flips:

    1. Inside the mm.add(DESKTOP_MOTION_QUERY, ...) callback, immediately after the
       existing setAttribute of data-motion-active, set
       data-motion-decided="scene" on the same root element.
    2. In that callback's cleanup function, after the existing removeAttribute of
       data-motion-active, set data-motion-decided="static" — the matchMedia cleanup
       runs on breakpoint change, so the marker stays truthful if the query stops
       matching mid-session.
    3. Immediately AFTER the mm.add(...) call (the callback fires synchronously when
       the query matches, so on desktop the attribute is already "scene" at this
       point), add the negative-branch default: if root does not yet have
       data-motion-decided, set it to "static". This is the stamp mobile and
       reduced-motion pages receive the moment the enhancer has run and DECLINED to
       build a scene.

    The marker is INERT: reference it from no stylesheet, no component logic, no
    Tailwind class. Update each module's "E2E signals" doc comment to list
    data-motion-decided alongside data-motion-active. Do NOT touch lib/gsap-gate.ts.
    Do not add or remove any jest test blocks (docs/metrics.json counts them).
  </action>
  <verify>
    <automated>cd frontend && npx jest components/marketing/__tests__/hero-scene.test.tsx components/marketing/__tests__/operator-entrance-scene.test.tsx 2>&1 | tail -4 && out=$(rg -uu -l 'data-motion-decided' app/ components/ lib/ --glob '!**/__tests__/**'); rc=$?; echo "rc=$rc files:"; echo "$out"</automated>
  </verify>
  <done>
    Both jsdom suites green (they never enter the canEnhance-guarded branch, so no
    expectation changes). The inertness grep lists EXACTLY the two enhancer modules
    and nothing else (rc=0, 2 files). Fail direction for the grep: it already
    returned rc=1 (zero files) on the pre-change tree this plan was cut from, so a
    2-file result is a real delta, not a pattern that always matches.
  </done>
</task>

<task type="auto">
  <name>Task 2: Replace all seven networkidle waits with deterministic anchors</name>
  <files>frontend/e2e/marketing-motion.spec.ts, frontend/e2e/csp-no-violations.spec.ts</files>
  <action>
    marketing-motion.spec.ts — four sites:

    - Line 43 (/ desktop): delete the networkidle wait. The very next statement
      already awaits expect(...toBeAttached()) on [data-motion-active='desktop'];
      give that expect an explicit generous timeout (15000ms) so it does not inherit
      the short expect default — determinism comes from the predicate, the timeout is
      just slack. Everything after (the 1400ms tween settle, door opacity, heatwash
      parallax) is unchanged.
    - Line 74 (/for-operators desktop): replace the networkidle wait with an
      auto-retrying wait for [data-motion-active='desktop'] (first(), toBeAttached,
      timeout 15000) — the operator hook stamps the same attribute, and the
      .gsap-word spans are created synchronously in the same callback, so the
      count assertion that follows is anchored.
    - Lines 124 and 161 (mobile loop and reduced-motion loop): replace each
      networkidle wait with an auto-retrying wait for
      [data-motion-decided='static'] (first(), toBeAttached, timeout 15000). This is
      the load-bearing anchor: the attribute only exists after the enhancer ran and
      chose the static branch, so the .gsap-word == 0 / data-motion-active == 0 /
      .pin-spacer == 0 assertions that follow are no longer vacuous-by-timing.
      Asserting the VALUE 'static' (not mere presence) also makes a wrongly-built
      scene fail the wait itself. KEEP the existing waitForTimeout(500) settles —
      they are not the flake source and removing them buys nothing.

    csp-no-violations.spec.ts — three sites (leave the /dashboard test alone):

    - Replace each waitForLoadState("networkidle") (lines 65, 85, 96) with
      waitForLoadState("load") followed by a positive render anchor:
      await expect on the page's first h1 (homepage, /for-operators) or the
      storefront's first h1 (storefront test) being visible, timeout 15000. Then the
      EXISTING fixed settles (2000 / 2000 / 3000 ms) run UNCHANGED — the
      violation-collection window must not shrink. Net effect: "load" fires when all
      initial resources have attempted to load (which is when violations fire), the
      anchor proves render happened, the settle catches stragglers — same coverage,
      no idle-detection dependency.

    Do NOT: add/remove any test() block, touch playwright.config.ts, add retries, or
    change any expect() predicate other than adding the anchor waits described.
  </action>
  <verify>
    <automated>cd frontend && out=$(rg -uu -n 'networkidle' e2e/marketing-motion.spec.ts e2e/csp-no-violations.spec.ts); rc=$?; echo "rc=$rc (expect 1 = zero matches) out=$out"; before=$(git show HEAD:frontend/e2e/marketing-motion.spec.ts | rg -uu -c 'networkidle'; git show HEAD:frontend/e2e/csp-no-violations.spec.ts | rg -uu -c 'networkidle'); echo "pre-change counts (positive control, expect 4 and 3): $before"; blocks=$(rg -uu -c '^\s*test\(' e2e/marketing-motion.spec.ts e2e/csp-no-violations.spec.ts); echo "test-block lines (expect unchanged vs HEAD): $blocks"</automated>
  </verify>
  <done>
    Zero networkidle matches in both files (rc=1 from rg), with the positive control
    showing 4 and 3 on the HEAD versions — proving the pattern and scope can match,
    per the suspect-the-instrument rule. test() block counts identical to HEAD (the
    mobile/reduced loops contribute the same block count), so docs/metrics.json and
    docs-freshness.sh are untouched by construction.
  </done>
</task>

<task type="auto">
  <name>Task 3: Rebuild the frontend image, run the fail-direction arms, then the clean pass</name>
  <files>frontend/e2e/marketing-motion.spec.ts, frontend/e2e/csp-no-violations.spec.ts</files>
  <action>
    Precondition — COMMIT Tasks 1+2 to the feature branch BEFORE any break arm
    (proof standards: the restore target must be a committed state; restores are
    verified by content, never by diff --stat).

    Rebuild (the marker is frontend source; the spec runs against the compose stack,
    and `compose start` never rebuilds): from the repo root,
    `docker compose -f docker-compose.full-stack.yml build frontend` then
    `up -d frontend`. Note the anchor is self-proving against staleness: a stale
    image lacks data-motion-decided, so the new mobile waits would time out — the
    test failing is the runtime-parity alarm, not a flake.

    Environment for every playwright run (from frontend/):
    `set -a; source ../.env; set +a; export E2E_VENDOR_PASSWORD="$KC_SEED_USER_PASSWORD"; export PLAYWRIGHT_BASE_URL=http://localhost:3000`.

    Break arms — clean -> arm -> clean, each arm's edit reverted by content
    (rg for the arm's unique token returns rc=1 afterwards):

    ARM A (anchor is load-bearing): in the mobile loop, change the wait's locator to
    the bogus [data-motion-decided-bogus='static']; run
    `npx playwright test e2e/marketing-motion.spec.ts --grep "degrades"`; expect
    FAILURE by wait timeout. Records that the wait is real, not decorative.

    ARM B (absence assertion can fail): restore A, then flip the mobile
    .gsap-word count expectation to toBeGreaterThan(0); same run; expect FAILURE
    with received 0 — proving the assertion executes AFTER a settled static decision
    and reads real DOM (a genuinely-empty count, not a pre-hydration accident).

    ARM C (CSP window still catches violations): restore B, then in the homepage CSP
    test, after the new waitForLoadState("load"), temporarily add a page.evaluate
    that creates a script element, sets its textContent to a no-op statement, and
    appends it to document.body — an un-nonced inline script the strict CSP must
    refuse; run `npx playwright test e2e/csp-no-violations.spec.ts --grep "homepage"`;
    expect FAILURE with the violation text in the assertion message. Records that
    the replacement window is live where it matters.

    Closing clean assertion (the only proof the restores happened): git status of
    the two spec files matches the committed state, and rg -uu for each arm token
    ('bogus', 'toBeGreaterThan(0)' in the mobile block, the injected-script marker)
    returns rc=1.

    Clean pass: `npx playwright test e2e/marketing-motion.spec.ts e2e/csp-no-violations.spec.ts`
    (both configured projects; the @desktop-only block is grepInverted from the
    mobile project by design). Capture rc on the same line. Expect all green,
    0 unexpected skips.

    Record in the SUMMARY: changing these specs changes the suite's specDigest, so
    every stored report predating this branch is VOID for
    scripts/check-e2e-skip-budget.sh (rc=2, failing closed as designed) — any gate
    claim needs a fresh FULL-suite run, which is out of scope here and must be
    logged, not papered over. The pre-existing 7/6 skip-budget state (#686) is
    untouched by this plan.
  </action>
  <verify>
    <automated>cd frontend && set -a && source ../.env && set +a && export E2E_VENDOR_PASSWORD="$KC_SEED_USER_PASSWORD" PLAYWRIGHT_BASE_URL=http://localhost:3000 && out=$(npx playwright test e2e/marketing-motion.spec.ts e2e/csp-no-violations.spec.ts 2>&1 | tail -6); rc=$?; echo "rc=$rc"; echo "$out"; rg -uu -n 'bogus|data-motion-decided-bogus' e2e/; echo "arm-residue rc=$? (expect 1)"</automated>
  </verify>
  <done>
    All three arms observed FAILING with their expected failure shapes (recorded
    output in the SUMMARY, both directions per arm), restores proven by content
    (arm-token rg rc=1, files match the committed state), and the final combined run
    of both spec files exits 0 against the freshly rebuilt compose frontend. If any
    arm PASSES, the corresponding check is vacuous and the task is NOT done — fix
    the anchor, do not delete the arm.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| none new | Changes are e2e test code plus one client-side DOM attribute with two static, developer-authored values |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-quick-260830-01 | Information Disclosure | data-motion-decided attribute in served DOM | accept | Reveals only whether the motion scene ran — already inferable from data-motion-active / .gsap-word; static values, no user data |
| T-quick-260830-02 | Tampering | CSP break arm (ARM C) injecting an inline script | mitigate | Arm exists only transiently on the feature branch, removed before commit of the final state; closing clean assertion (rg rc=1) proves removal by content |
| T-quick-260830-SC | Tampering | package installs | accept | No packages installed by this plan — zero new dependencies |
</threat_model>

<verification>
- `rg -uu -c 'networkidle' frontend/e2e/marketing-motion.spec.ts frontend/e2e/csp-no-violations.spec.ts` → rc=1 (zero matches); positive control on HEAD versions shows 4 and 3.
- `npx jest components/marketing/__tests__/hero-scene.test.tsx components/marketing/__tests__/operator-entrance-scene.test.tsx` green.
- Three break arms each observed failing (wait-timeout, received-0, violation-listed), then restored by content.
- Combined run of both spec files exits 0 against the rebuilt compose frontend at :3000, both projects.
- No changes to playwright.config.ts, no retries added, no test() blocks added/removed, fixed CSP settles unchanged.
</verification>

<success_criteria>
- All 7 networkidle waits gone; every replacement is a predicate on page state
  (attribute attached / element visible / load event), never an idle heuristic.
- Absence assertions strictly STRONGER than before: anchored to
  data-motion-decided='static', which was demonstrated load-bearing (ARM A) and
  falsifiable (ARM B).
- CSP window demonstrated still-live (ARM C) with unchanged settle durations.
- Both specs green against the rebuilt compose stack; specDigest/skip-budget VOID
  consequence recorded in the SUMMARY.
</success_criteria>

<output>
Create `.planning/quick/260830-hjs-fix-flaky-networkidle-waits-in-marketing/260830-hjs-SUMMARY.md` when done, including the recorded fail-direction outputs for all three arms and the marker-vs-pure-spec decision rationale.
</output>
