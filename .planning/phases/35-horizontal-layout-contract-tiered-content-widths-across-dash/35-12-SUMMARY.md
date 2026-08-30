---
phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
plan: 12
subsystem: testing
tags: [runtime-parity, css-diff, playwright, falsifiability, coverage-honesty, accessibility, docker]

# Dependency graph
requires:
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: "08"
    provides: "e2e/layout-width-contract.spec.ts — the instrument this plan runs against a genuine pre-change build, and the recorded stale-runtime debt"
  - phase: 35-horizontal-layout-contract-tiered-content-widths-across-dash
    plan: "11"
    provides: "the regenerated docs/metrics.json this plan re-checks, and the contract document"
provides:
  - "the DELIVERED runtime proven to be this branch, by content and by chunk identity — the phase's headline debt, discharged"
  - "the pre/post filtered stylesheet diff, with a two-directional control on the filter itself"
  - "the pre-change-tree control arm CONTEXT.md section 6 names: 21/21 RED on a real merge-base build, 21/21 GREEN on the delivered runtime"
  - "T-35-13 exercised for real over a live STOMP feed, after measuring that neither named spec does so"
  - "T-35-15 run at 320px against the rebuilt runtime"
  - "the skip-budget gate moved from rc=2 VOID to an answerable rc=1, with the honest number"
  - "the measured finding that the report-only a11y sweep does not evaluate the scroll rule either"
affects: [35-13]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "postcss-ast-partitioned-diff: mobile-relevance decided by the at-rule chain, not by a regex over text"
    - "filter-armed-in-both-directions: a sub-lg rule must appear and an above-lg rule must be dropped, or the filter proves nothing"
    - "port-resolved-pid teardown: a server is stopped via its LISTENING PORT, never by matching a pattern the caller's own command line contains"

key-files:
  created:
    - .planning/phases/35-horizontal-layout-contract-tiered-content-widths-across-dash/35-12-SUMMARY.md
  modified:
    - frontend/app/dashboard/kitchen/__tests__/page.test.tsx
    - frontend/app/dashboard/orders/[id]/__tests__/detail-tier.test.tsx

key-decisions:
  - "No new issue was filed: #689 already states the jsdom coverage gap in full, and a duplicate would split the record. This plan's new browser evidence was added to it as a comment instead"
  - "The marketing-motion timeout is attributed to pre-existing #687, proven by a byte-identical hero-scene.tsx blob and a moving failure across runs — not adjusted away and not silenced"
  - "The two stray sm: rules were FIXED rather than explained away, because the diff exists to surface exactly that class of thing"
  - "The skip budget is reported at 7/6 with its undeclared title named; no ALLOW was added, because declaring a skip this plan did not cause would be papering over #686"

patterns-established:
  - "A source change after a rebuild re-opens runtime parity — the gate caught this plan's own commit and forced a second rebuild"
  - "scripts/sync-runtime.sh needs TWO passes when a stale service has a built dependency: compose rebuilds the dependency image but only starts its container"

requirements-completed: []
requirements-progressed: [UIX-07, UIX-08, UIX-09]

# Metrics
duration: 165min
completed: 2026-08-30
---

# Phase 35 Plan 12: Runtime Parity, the CSS Diff and the Pre-Change Arm Summary

**The delivered container was serving the pre-phase stylesheet — `.container{max-width:1400px}` and zero tier attributes — and every measurement in this phase until now was taken against something else; it is now proven to be this branch by content and by chunk identity, and the width contract has been shown failing 21/21 on a real build of the merge base.**

## Performance

- **Duration:** ~165 min
- **Tasks:** 3 of 3
- **Files modified:** 2 (both comment-only), plus this SUMMARY
- **Commits:** 1 implementation + 1 for this SUMMARY
- **Branch:** `feature/35-horizontal-layout-contract`, 67 ahead / **0 behind** `origin/main`

---

## (i) THE HEADLINE DEBT — the delivered runtime was stale, and it is now not

### What was actually being served, measured before anything was touched

```
scripts/check-runtime-freshness.sh                                          rc=1
  frontend   DRIFT  [image-not-rebuilt]  image tagged 2026-08-29 15:42:00 UTC
                    / newest build-input commit 34256f5c (2026-08-29 19:40:26 UTC)
  core-java / edge-go / mcp-server  FRESH
FAIL: 1 of 4 running built service(s) do not match the source tree (0 unverified).
```

Corroborated structurally first, which is the cheaper and clearer signal:

```
curl localhost:3000/          data-width-tier="marketing"   0
                              data-width-tier (any)         0
              CONTROL         "Independent UK kitchens"     2   <- the page really rendered

served stylesheet /_next/static/chunks/28pci-x3j98a8.css   96925 bytes
  .container{width:100%;margin-left:auto;margin-right:auto;padding-left:2rem;padding-right:2rem}
  .container{max-width:1400px}                              <- the inherited cap the phase retired
  .max-w-shell / .max-w-detail / .max-w-marketing           absent
```

**The running container was serving the exact stylesheet this phase exists to replace.**

### The identity proof, which is stronger than the gate

The pre-change control build (Task 2, from merge base `96c8d794`) emitted
**`28pci-x3j98a8.css`** — the *same content-hashed chunk name* the stale container was serving.
A Next.js CSS chunk name is a hash of its content, so this is not a resemblance: the delivered
runtime was byte-for-byte the merge-base stylesheet.

### The rebuild, and the two-pass behaviour it exposed

`scripts/sync-runtime.sh` (never `docker compose start`/`restart`, neither of which builds):

| Pass | Result |
|---|---|
| 1 | frontend rebuilt → **FRESH**; but `core-java` flipped to `DRIFT [container-not-recreated]` — image tag moved to `sha256:29b3f02b3181` while the container kept executing `sha256:3f953e0798a1`. rc=1 |
| 2 | core-java recreated → **4/4 FRESH, 0 unverified**. rc=0 |

**This is a reproducible finding, not a one-off:** it happened again later in the plan, identically.
`docker compose up -d --build --force-recreate <service>` rebuilds a *dependency's* image but only
**starts** that dependency's container, so a single `sync-runtime` pass cannot converge when the
stale service has a built dependency. The gate catches it precisely because it distinguishes
`[image-not-rebuilt]` from `[container-not-recreated]`.

**The core-java rebuild red-ed the alert-metrics gate, as expected rather than as a finding:**

```
FAIL: M-1 rule 'NoOrdersCreated' selector matches ZERO series — this rule can never fire
       CAUSE: ... expected on a freshly rebuilt stack ... FIX: bash scripts/seed-order-metric.sh
bash scripts/seed-order-metric.sh   ->  placed HTTP 201, series 0 -> 1   rc=0
scripts/check-alert-metrics.sh      ->  rc=0
```

### Parity proven BY CONTENT, out of the running artefact

```
scripts/check-runtime-freshness.sh   PASS: 4 running built service(s) match the tree (0 unverified)  rc=0
scripts/check-branch-behind-base.sh  PASS: 67 ahead, 0 behind origin/main                            rc=0

curl localhost:3000/         data-width-tier="marketing"        6   (4 bands + 2 chrome rails)
                             escaped in the RSC flight payload  4
             CONTROL         "Independent UK kitchens"          2

served stylesheet  .max-w-shell{max-width:1700px}
                   .max-w-detail{max-width:1100px}
                   .max-w-marketing{max-width:1280px}
                   .container{ rules            0
                   max-width:1400px             0
   CONTROL         max-width: occurrences      26
   CONTROL         brand oxblood '58 11 13'     9
   CONTROL         max-width:9999px (absent)    0   <- the probe can report an absence
```

The 6 live-backed marketing bands are exactly the figure 35-08 measured against a live stack, and
the controls are what make the two zeros evidence about the stylesheet rather than about the search.

### The gate caught this plan's own commit, and that is the rule working

After committing the fix in (ii), `check-runtime-freshness.sh` went **back to rc=1** — the two
edited files are inside the frontend build path. That is not ceremonial here: the fix *changes the
emitted CSS*, so the delivered stylesheet genuinely no longer matched the tree. Rebuilt (two passes
again), reseeded the order metric, and re-proved parity. The final delivered chunk is
**`3otvctlq2qoxs.css`, 96686 bytes — byte-identical to the chunk plan 35-02 recorded**, which is an
independent confirmation that the two stray rules removed in (ii) were wave 3's *only* net addition
to the stylesheet.

---

## (ii) THE CSS DIFF — and the thing it found that nobody was looking for

### Method

Both stylesheets generated with the **same command** and the **same content set**: each tree's own
committed `tailwind.config.ts` (content globs are string-identical between the trees) and
`app/globals.css` (blob-identical, `dd3a2b51…` at both), through the repo's own Tailwind CLI 3.4.19.

Mobile-relevance is decided **structurally**, by walking the PostCSS AST and reading each rule's
enclosing at-rule chain: a rule is excluded only when some enclosing `@media` carries a
`min-width` at or above the `lg` breakpoint (1024px), because such a rule provably cannot apply at a
small viewport. Rules with no media query, sub-`lg` min-widths, `max-width` queries,
`prefers-reduced-motion`, `prefers-color-scheme` and `print` all stay in the mobile set.

| | rules total | mobile set | above-lg set |
|---|---|---|---|
| pre (`96c8d794`) | 1286 | 1250 | 36 |
| post (branch) | 1285 | 1251 | 34 |

### Instrument defect, caught before any conclusion was drawn

The first generation ran with `NODE_ENV=production`, which **overrides `--minify=false`**. The
output was one 92 KB line, so every line-anchored probe returned 0 — including
`max-width: 1400px`, which is genuinely present in that tree. Had that been read as a result it
would have said "the container rule is already absent from the pre-change build", which is false.
It was caught because the `@media` and rule counts were also implausible. Regenerated with
`env -u NODE_ENV`; every number below is from the unminified output.

### THE FINDING — the first diff was NOT the four expected changes

```
-(unconditional) ||| .\!container { margin-left:auto !important; ... width:100% !important }
-(unconditional) ||| .container   { margin-left:auto; margin-right:auto; padding-left:2rem; padding-right:2rem; width:100% }
+(unconditional) ||| .max-w-detail    { max-width:1100px }
+(unconditional) ||| .max-w-marketing { max-width:1280px }
+(unconditional) ||| .max-w-shell     { max-width:1700px }
+@media (min-width: 640px) ||| .sm\:max-w-lg   { max-width:32rem }     <-- NOT EXPECTED
+@media (min-width: 640px) ||| .sm\:max-w-none { max-width:none }      <-- NOT EXPECTED
```

Two live rules had entered the **below-`lg`** set that the phase never intended to ship. Traced,
not guessed:

```
sm:max-w-lg    branch: app/dashboard/orders/[id]/__tests__/detail-tier.test.tsx:95   merge base: none
sm:max-w-none  branch: app/dashboard/kitchen/__tests__/page.test.tsx:22              merge base: none
CONTROL  max-w-[68ch] present in BOTH trees (2 files each) — the searches work
```

Both are **prose inside comments** that spelled a real utility as an example. Tailwind's scanner is
lexical and the content globs cover `app/**` including `__tests__/`, so a comment naming a utility
generates that utility. Neither is applied by any shipped element:

```
sm:max-w-lg / sm:max-w-none in a class context, tests excluded  ->  matches=[] rc=1
CONTROL  sm:max-w-md  ->  components/storefront/cart-drawer.tsx:63   (a genuinely applied one)
```

**Fixed, not explained away** (commit `59b80ce0`), following the exact precedent already on this
branch — `34256f5c`, *"describe the tier token in the ARM C note rather than spelling it"*. A sweep
with a control confirms the only remaining variant-prefixed cap tokens under the content globs are
four genuinely-applied classes (`cart-drawer`, `sheet` ×2, `toast`).

### The result after the fix — exactly the phase's intended change

```
-(unconditional) ||| .\!container { ... }
-(unconditional) ||| .container   { ... }
+(unconditional) ||| .max-w-detail    { max-width:1100px }
+(unconditional) ||| .max-w-marketing { max-width:1280px }
+(unconditional) ||| .max-w-shell     { max-width:1700px }

changed lines: 5      above-lg set: -2, being the two @media (min-width: 1400px) container caps
```

Note the plan expected "the retired scaffold rule" singular; it is **two** — `.container` and its
`!important` variant `.!container`.

### The control the plan requires — the filter armed in BOTH directions

An empty or small diff is worthless unless the diff can show a difference *and* the filter can drop
one. Bracketed **clean → arms → clean**:

| Arm | Input | Result |
|---|---|---|
| **CONTROL 0** | a file diffed against itself | empty, rc=0 |
| **opening clean** | no probe; `git status` 0 paths | mobile set == post-fix baseline |
| **ARM F1** | `sm:max-w-shell` (below lg) planted in a probe file | `+@media (min-width: 640px) ||| .sm\:max-w-shell { max-width:1700px }` — **surfaced** |
| **ARM F2** | `xl:max-w-shell` (above lg) | mobile-set diff **0 changed lines**; the SAME rule appears in the desktop-set diff as `+@media (min-width: 1280px)` — dropped, not lost |
| **closing clean** | probe deleted | probe absent by content, `git diff --quiet HEAD` rc=0, mobile set == baseline |

ARM F2 is the one that matters: without it, "nothing below `lg` moved" would be consistent with a
filter that drops everything.

### The one below-`lg` change, and why it is inert — measured twice

`.container` is the only removal that could bind at a small viewport. Two independent measurements:

**Cascade order, read out of the pre-change stylesheet** (not inferred):

```
.container at line 641   { width:100%; margin-right:auto; margin-left:auto; padding:2rem ... }
.mx-auto   at line 928
.p-4       at line 3007  { padding: 1rem }
```

`.p-4` and `.mx-auto` are *later* at equal specificity, and the shell band carries both in **both**
trees — so the container's padding and auto-margins were already dead before the change. Only
`width:100%` was unduplicated, and a block-level child of `main` fills its parent by default.

**In a real browser at 390px, on both builds** — the one surface `.container` was ever applied to:

| | merge-base build (:3100) | branch runtime (:3000) |
|---|---|---|
| band width | 390 | 390 |
| padding-left / right | 16px / 16px | 16px / 16px |
| margin-left / right | 0px / 0px | 0px / 0px |
| document horizontal overflow | 0 | 0 |
| computed `max-width` | `none` | `1700px` |

Identical in every dimension that renders. The only difference is a 1700px cap that cannot bind
against a 390px parent.

---

## (iii) THE PRE-CHANGE CONTROL ARM — the one CONTEXT.md section 6 names

35-08 explicitly deferred this, recording that its five arms "are the sharper, cheaper ones; none of
them is that arm".

**The build.** `git archive 96c8d794` into a scratch tree; `node_modules` **copied** (not
hardlinked — inode identity printed and confirmed distinct, link counts 4 vs 1, after 35-09's
`cp -al` incident), `package.json` and `package-lock.json` blob-identical between the trees so the
dependency graph is the same. `npm run build` rc=0. Served on **:3100**, which is one of the two
ports the Keycloak realm accepts as a redirect URI.

**The server was genuinely up and serving, so a failure is for the right reason:**

```
:3100 /  200   /dashboard  200   /for-operators  200   /legal/privacy  200
/ bytes 64797   CONTROL "Independent UK kitchens" 2      data-width-tier (any) 0
served CSS /_next/static/chunks/28pci-x3j98a8.css   max-width:1400px x2   tier rules 0
```

**The result:**

```
PLAYWRIGHT_BASE_URL=http://localhost:3100 npx playwright test e2e/layout-width-contract.spec.ts --project=desktop
  21 failed, 0 passed      rc=1
```

All twelve Marketing assertions across `/`, `/for-operators`, `/business-model-guide`,
`/legal/privacy` at 1440/1920/2560:

```
TimeoutError: page.waitForSelector: Timeout 15000ms exceeded.
  - waiting for locator('[data-width-tier="marketing"]') to be visible
```

All nine Dashboard assertions (Shell/Index/Detail × three viewports):

```
TimeoutError: page.waitForSelector: Timeout 30000ms exceeded.
  - waiting for locator('[data-width-tier="index"]') to be visible
```

**The dashboard arm failed for the RIGHT reason, and that was checked rather than assumed.** The
stored `error-context.md` page snapshot shows the *logged-in dashboard* — the sidebar, the shop
switcher populated with all three seeded shops, and `Admin User / admin@jtoye.com`. Keycloak login
succeeded and the real dashboard rendered; the tier attribute simply does not exist on that tree.

**And against the delivered runtime, twice** (once before the (ii) fix, once against the final
rebuilt image):

```
PLAYWRIGHT_BASE_URL=http://localhost:3000 ...   21 passed (38.6s)  rc=0
                                                21 passed (38.5s)  rc=0
```

This is the first time in the phase the width contract has been measured against the **delivered
artefact** rather than a host-served `next start`.

---

## (iv) THE BROWSER SWEEP — every listed spec, against the rebuilt runtime

E2E fixtures were reseeded (`scripts/seed-e2e-fixtures.sh`, rc=0) before any skip was trusted, and
again after the T-35-13 probe mutated order state.

### Public and marketing — owed from 35-06

| Spec | Result |
|---|---|
| `public-layout.spec.ts` | **38 passed** rc=0 |
| `landing-webperf.spec.ts` (both projects) | **9 passed** rc=0 |
| `marketing-motion.spec.ts` | 10 passed in isolation; see the flake finding below |
| `near-you-row.spec.ts` | **12 passed** rc=0 |
| `marketing-dish-scroller.spec.ts` | **5 passed** rc=0 |

**The desktop CLS arm, read out of the delivered container** — 35-09 measured this on a host-served
build and its closing arm showed it RED against the then-stale image. It now reproduces exactly:

```
/ — LCP=800ms CLS=0.0362 at 1440x900, 4x CPU · Marketing band 1280px, 4 in main / 6 document-wide
    · control 0.1316 · record 0.0362
```

Mobile stays at the pre-existing `CLS=0.1793` — unchanged, which is the designed outcome.

**The dish-scroller — the inverted risk, its own line as the plan requires.** It did **not** red.
The branch it takes was measured directly on the delivered runtime rather than inferred:

```
 390px  scrollWidth 692 / clientWidth 358, 3 cards  -> overflows = true   (OVERFLOW branch)
1440px  1216 / 1216                                 -> overflows = false  (row-fits branch)
1920px  1216 / 1216                                 -> false
2560px  1216 / 1216                                 -> false
```

That reproduces 35-06's calibrated structural model exactly, including its predicted 1216px
post-change client width at 1440. The rail does **not** now fit at mobile, so the product question
the plan flagged ("the finding is that the rail now fits") does not arise.

### Dashboard — owed from 35-03 and 35-04

| Spec | Result |
|---|---|
| `dashboard-mobile.spec.ts` | **48 passed** rc=0 |
| `media-review-320.spec.ts` | **2 passed** rc=0 (T-35-15 — see below) |
| `kitchen-flow.spec.ts` | **16 passed** rc=0 |
| `stomp-relay.spec.ts` | **4 skipped** — the declared ALLOW (needs `--scale core-java=2`) |
| `webhooks-flow.spec.ts` | **2 passed** rc=0 |
| `webhooks-webperf.spec.ts` | **6 passed** rc=0 |
| `cookie-notice-layout.spec.ts` | **2 passed** rc=0 |
| `unsubscribe-flow.spec.ts` | **6 passed** rc=0 |

### Full suites

```
cd frontend && npx jest        141 suites, 1503 tests, 2 snapshots — all passed   rc=0
npx playwright test (both projects, twice)
   run 1   323 results   314 passed   0 failed   2 timedOut   7 skipped
   run 2   323 results   315 passed   0 failed   1 timedOut   7 skipped
```

The Jest figures match `docs/metrics.json` exactly (1503 / 141); this plan's edits are comments, so
no block count moved and no regeneration was owed — confirmed by `docs-freshness.sh` rc=0 and
`check-doc-metrics.sh` rc=0 (37/37 claims).

### The `marketing-motion` timeout — a defect in the instrument, and proven so

| Run | Non-passing |
|---|---|
| 1 | `timedOut` **[mobile]** and **[desktop]** `/ degrades to fully-visible static content` |
| 2 | `timedOut` **[mobile]** `/ builds no GSAP scene under prefers-reduced-motion: reduce` — a **different test**; desktop passed |

Suspected the instrument first, then proved it:

- **In isolation the file is green three times** — 10/10, 10/10, 4/4.
- **Run in the exact full-suite order** behind `landing-webperf` and `marketing-dish-scroller`:
  10/10 green. So the immediate predecessors are not the cause.
- **Both are bare `Test timeout of 60000ms exceeded.`** — never an assertion failure.
- **The failure MOVES between runs**, which is the flake signature this repository already uses as
  its discriminator.
- **`/` SSR latency is not the cause:** ten sequential timings returned 0.010–0.018 s.
- **Issue #687 already records this exact test as flaky at ~1 in 3**, filed 2026-08-29T16:29Z —
  *before* this branch existed — and explicitly concludes it is pre-existing.
- **The phase cannot be the cause, structurally:** `components/marketing/hero-scene.tsx`, which owns
  the `.gsap-word` selector, is **blob-identical** between the merge base and HEAD
  (`5bbe77ddd8e5ad1420367d8a093147af038b0942` at both); this branch changed no motion/GSAP source
  (control: the same filter does match the dashboard `page.tsx` files); and both tests run at 375px,
  where the only rule the phase adds to that route is unconditional at 1280px and cannot bind.

Recorded as a comment on **#687** with the broader symptom set. Not adjusted, not silenced.

### Skip budget — the VOID is cleared, and the number is over budget

The gate was `rc=2 VOID` for the whole phase (35-08, 35-10, 35-11 each recorded it) because its
stored report predated this branch's spec changes. A fresh full-suite JSON report clears it:

```
freshness : specDigest 53a74f730a2ffc7a… matches the tree (content, not mtime)
tests     : 323 total, 7 skipped (budget 6)
FAIL: S-1 7 skipped test(s) exceeds the declared budget of 6
FAIL: S-2 undeclared skip: "onboarding-blocked-flow.spec.ts › bad company number -> fix inline ->
          re-run checks -> honest in-review @desktop-only"
rc=1
```

**Measured identically against both independent full runs.** The gate now *answers*, and its answer
is a real violation rather than "cannot tell" — that is the actual improvement this plan delivers on
this gate.

The cause is read from the report's **own per-test annotation**, not repeated from #686:

> `Target tenant onboarding is already LIVE/terminal — this blocked-journey spec needs a
> fresh/disposable tenant. Skipping to avoid failing against or mutating the live demo.`

Reseeding does not clear it — `seed-e2e-fixtures.sh` ran twice and the skip persists, because the
demo tenant's onboarding is in a terminal state. **This is exactly #686 and it is unchanged by this
phase.** The 7 are `stomp-relay` ×4 + `vendor-refund-flow` ×2 (both declared) + this one.
**This phase contributes zero skips**: the credential precondition was satisfied — option (a), as
35-08 chose — so `layout-width-contract.spec.ts` reports 21 passed and 0 skipped.

No `ALLOW` was added. Declaring a skip this plan did not cause, to make a gate green, is the
papering-over the plan forbids; and the conf's own comment currently asserts this skip "is now gone
entirely", which is measurably false and belongs in #686 rather than in a new exemption.

### Coverage state, in CONTEXT.md section 5's authoritative wording

The dashboard-tier assertions (Shell / Index / Detail) are **covered by a spec that no current tree
executes**, citing open issue **#683**: the per-PR browser gate runs only `public-layout.spec.ts`
and `public-a11y.spec.ts`, and the full-suite nightly lane — the project's only full-suite E2E
instrument — is dark. **Everything this plan measured locally is a one-off observation by this plan,
not standing cover.** The same root cause applies to **#686**, which is why the skip-budget
precondition had to be satisfied by hand here rather than trusted to CI. Nothing in this record is
described as "covered nightly".

---

## (v) THE TWO OUTSTANDING THREATS — exercised, not reasoned

### T-35-15 — media review at 320px: RUN

`e2e/media-review-320.spec.ts` — **2 passed** (mobile and desktop projects), against the rebuilt
runtime, with fixtures seeded. The spec pins `viewport: { width: 320, height: 720 }`, drives a real
Keycloak login, navigates `/dashboard/media/review` on the running stack, and asserts all three
media states render, both FAILED actions are visible, and nothing overflows. 35-04 recorded this as
"reasoned inert, not run"; it is now run.

### T-35-13 — KDS reflow under a live feed: the named instruments do NOT exercise it

Before running anything, the two specs 35-04 named were checked:

- **`kitchen-flow.spec.ts:213` STUBS the STOMP websocket endpoint** — *"so a real broker is not
  needed"*. No live event ever reaches the board there.
- **`stomp-relay.spec.ts` is a declared skip** (needs a two-replica stack).

So neither exercises the threat, and reporting their green as coverage would have been false. A
temporary probe drove the real thing against the delivered runtime — real vendor session, no route
stubs, real websocket — and was **deleted after the run** (verified absent by content, `git status`
0 paths, `git diff --quiet HEAD` rc=0).

**Non-vacuity gates first**, so a probe that never connected could not report a clean reflow: the
websocket must be observed to open and to receive frames. Measured `sockets=1`, `framesIn` 1 → 2
after a real guest order → 3 after a real bump.

**Arm 1** posted a real guest order to `/public/shops/{slug}/orders` (HTTP 201). The board's ticket
count did **not** change — recorded as an *explained non-event*, not as a reflow: a guest order is
created `PENDING` and the board renders only `CONFIRMED`/`PREPARING`/`READY`.

**Arm 2** clicked a real bump button (`Start Preparing`), posting a genuine `CONFIRMED → PREPARING`
transition through the live stack, which is what drives `AnimatePresence mode="popLayout"`
(`kitchen/page.tsx:834`) with `layout` on each ticket (`:852`). A poll asserted the board's control
set actually **changed**, or the probe would have proven nothing.

Geometry at 1920, across all three snapshots — identical:

```
band 1600  shellWidth 1664  shellContentBox 1600  shell max-width 1700px
grid 4 columns "388px 388px 388px 388px"
docScrollWidth 1920 == docClientWidth 1920   (no horizontal overflow)
```

**A structural finding that reframes the threat:** the board's column count is
`grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4` — gated on the **viewport**, not the band.
The Shell tier therefore *cannot* change how many columns the board has; it changes only how wide
each column is. The plan's "more columns changes the reflow" hypothesis does not hold, and the
measurement confirms 4 columns before and after the live event on both trees.

**The probe was armed:** run against the pre-change runtime it fails at
`waiting for locator('[data-width-tier="index"]')`, rc=1 — so it is bound to the real tiered board.

---

## (vi) THE ACCESSIBILITY RULE — verified where it can be, and the honest limit

`scripts` and the installed library were read rather than the claim inherited. `axe.js:19610-19613`:

```js
function getScroll(elm) {
  var buffer = ... : 0;
  var overflowX = elm.scrollWidth > elm.clientWidth + buffer;
  var overflowY = elm.scrollHeight > elm.clientHeight + buffer;
  if (!(overflowX || overflowY)) { return; }   // rule never applies
```

Measured directly in jsdom on a **deliberately overflowing** element:

```
scrollWidth = 0   clientWidth = 0   =>  (0 > 0 + 13) = false
```

Additionally measured: the four `jest-axe` consumers in this tree are all public/legal/storefront
components — **no dashboard surface uses `jest-axe` at all**.

**The nightly sweep, run against the rebuilt runtime** (its own header states it is REPORT-ONLY, and
it is described that way here, never as a gate):

```
[dashboard-a11y-nightly] /dashboard:          0 violation rule(s)
[dashboard-a11y-nightly] /dashboard/orders:   0 violation rule(s)
[dashboard-a11y-nightly] /dashboard/products: 0 violation rule(s)
1 passed   rc=0
```

**That 0 is not coverage of the rule either, and saying so is the point.** The products
`overflow-x-auto` region was measured at five viewports on both builds:

| viewport | merge base | branch head | `scrollWidth > clientWidth + 13`? |
|---|---|---|---|
| 390 | 308 / 308 | 308 / 308 | false |
| 1024 | 654 / 654 | 654 / 654 | false |
| 1440 | 1070 / 1070 | 1070 / 1070 | false |
| 1920 | 1286 / 1286 | **1550 / 1550** | false |
| 2560 | 1286 / 1286 | **1586 / 1586** | false |

The region never overflows on the current seed, so `getScroll` returns early in a real browser too.
The browser sweep is a strictly better instrument than jsdom — it has layout — but on this data it
is green for the same structural reason. Citing it as evidence about A11Y-3 would repeat, one level
up, the error the issue was opened about.

**What the browser run does verify, and jsdom cannot:** the affordances survive a real layout.
Identical on both builds at all five viewports —
`role="region"`, `aria-label="Products table, scroll horizontally for more columns"`, `tabindex="0"`.

**Direction of risk, now measured rather than reasoned:** widening grows the region's client width
(1286 → 1550 at 1920, 1286 → 1586 at 2560) against unchanged content, so overflow becomes strictly
*less* likely and the rule can match strictly fewer elements. Below `lg` the numbers are
byte-identical.

### The follow-up — no new issue, because one already exists

**Issue #689** — *"jest-axe dashboard gate cannot evaluate scrollable-region-focusable (geometric
rule, jsdom has no layout) — A11Y-3 is ungated per-PR"* — was filed 2026-08-29T17:30Z during this
phase's research and already states every element the plan requires: the rule, why jsdom cannot
evaluate it, that the per-PR gate has been passing vacuously for it, that the only verification is
the report-only nightly sweep (dark per #683), and a proposed direction without deciding it.

Filing a second issue would split the record, so this plan **commented on #689** instead
(`#issuecomment-5465426701`), adding what it did not have: the measurement that the report-only
sweep does not evaluate the rule either, the five-viewport before/after table, the affordance
verification, and a suggestion that any future assertion ship a **seeded overflow fixture** — or it
will inherit exactly this problem and pass without exercising the rule. This remains out of scope
for the phase.

---

## (vii) THE PHASE INCREMENTAL BETTERMENT LEDGER

| # | Displaced good | Displaced by | Evidence it was preserved or bettered |
|---|---|---|---|
| 1 | `.container`'s `padding-left/right: 2rem` on the shell band | 35-01 (plugin retired) / 35-02 (class removed) | **Never live.** Cascade order read out of the pre-change stylesheet: `.container` line 641, `.p-4` line 3007 — later, equal specificity, shorthand. Browser at 390px: padding `16px/16px` on **both** builds |
| 2 | `.container`'s `margin-left/right: auto` | same | **Duplicated already.** `.mx-auto` at line 928 (later than 641) and present on the same element in both trees. Browser at 390px: margins `0px/0px` on both builds |
| 3 | `.container`'s `width: 100%` | same | **Structurally replaced** — a block child of `main` fills it. Measured: band 1664 when `main` is 1664 (1920), 1184 at 1440, 390 at 390 |
| 4 | The landing hero's reading measure | 35-06 (ORCH-04) | **PRESERVED, byte-identical line** in both trees: `<p className="mt-4 max-w-xl text-lg text-slate-600">`. Fail direction: 35-06 ARM E |
| 5 | Small-viewport padding on every touched band | 35-02/03/04/05/06/07 | **PRESERVED.** Filtered CSS diff shows no padding utility moved in the below-`lg` set; browser at 390px shows identical padding; 35-06 ARM D is the fail direction |
| 6 | The products scroll region's role, name and tabindex (#685 A11Y-3) | 35-03 (index tier) | **PRESERVED, verified in a real browser** on both builds at five viewports (table in section vi). Bettered in direction: the region is wider, so it can overflow less |
| 7 | The storefront rails' agreement with their content | 35-07 (ORCH-01) | **PRESERVED.** `/shop` at 1440 on both builds: header rail 1280px at left 80, content 1280px at left 80 — they share a left edge on both |
| 8 | Already-narrower surfaces keeping their measure (ceiling-not-target) | 35-05 exceptions ledger | **PRESERVED.** `/track`'s `max-w-lg` surface measures **512px on both builds** at 1440 — it did not follow the band |
| 9 | The prose measure, "untouched throughout" | ORCH-06 | **NOT untouched — and the correction is a betterment.** Measured 6 → 8 occurrences (`policy-page.tsx` 4→5, its a11y test 2→3). ORCH-06 widened the policy band to 1280, and a further 68ch clamp was added so the prose did **not** widen with it. The ceiling-not-target rule was *extended*, not displaced |
| 10 | The landing's layout stability | 35-06 | **BETTERED, and now confirmed on the delivered runtime.** Desktop CLS 0.1316 → 0.0362 (a 72% fall), reproduced out of the container at `LCP=800ms CLS=0.0362`. Mobile unchanged at 0.1793 |
| 11 | The dashboard band's own width | 35-02 | **BETTERED**, measured on real builds of both trees: 1440 unchanged at 1184; **1920 1400 → 1664 (+264)**; **2560 1400 → 1700 (+300)** |

**Regression by omission, checked:** nothing this phase touched renders an empty state, a dropped
capability or a blank surface. The eight before/after captures are the visual proof.

### The captures (Task 2 Part C)

Eight full-page screenshots, taken **after scrolling** the whole page so scroll-reveal content is
revealed, saved where the 35-13 owner gate can reach them:

```
/home/sanmi/IdeaProjects/JToye_OaaS_2026/frontend/e2e-artifacts/35-12/
  35-12-prechange-orders-1920.png    35-12-current-orders-1920.png
  35-12-prechange-orders-2560.png    35-12-current-orders-2560.png
  35-12-prechange-finance-1920.png   35-12-current-finance-1920.png
  35-12-prechange-finance-2560.png   35-12-current-finance-2560.png
```

Geometry recorded beside each: pre-change carries no tier and `main` 1664/2304; current declares
`shell` with band **1664 at 1920** (fluid — the 1700 cap does not bind until ~1956px viewport) and
band **1700 at 2560** (the cap binds). Document overflow 0 in all eight. Both 2560 orders captures
were read back by eye: the pre-change one reproduces CONTEXT.md's persuasive artefact — a
six-column table confined to 1400px with a wide empty gutter — and the current one shows the same
six columns and the same data spread across 1700px. Neither page is broken or empty.

**A screenshot cannot verify motion, and none of these is offered as motion evidence.**

---

## (viii) CROSS-CUTTING QUALITY CONTRACTS — all five, N/A written out

- **Web performance — LIVE.** Desktop `/` CLS **0.0362** against control 0.1316 and record 0.0362,
  LCP 800 ms, at 1440×900 with 4× CPU throttling, **read out of the delivered container**. Mobile
  `/` unchanged at CLS 0.1793 / the declared budget breach that pre-dates the phase.
  `webhooks-webperf` 6 passed. Every number is from a throttled profile, never unthrottled
  localhost. The filtered CSS diff also bounds the payload: net **−2 rules** and a stylesheet
  61 bytes smaller than before this plan's fix.
- **SEO — N/A, with reason.** A width changes no title, meta description, canonical, Open Graph tag,
  JSON-LD block, `sitemap.xml` entry, `robots.txt` directive or crawlable `<a href>`. This plan
  edits two test-file comments and touches no markup, metadata or route.
- **AI agent-readiness — N/A.** No API surface, endpoint, error contract, credential scope, OpenAPI
  document or MCP tool changed. No mutating endpoint was added, so the Idempotency-Key contract is
  not engaged.
- **Security — the phase threat model, consolidated.** No new trust boundary, input, credential,
  data flow or dependency was introduced by this plan or by the phase. ASVS L2: V4 does not apply
  (no authorisation decision is made by a width, and a wider band cannot reveal a row RLS withheld);
  V5 does not apply (every tier is a compile-time member of a closed union); **V14 is satisfied** —
  every width ships as a generated utility in the external stylesheet, verified by reading the three
  rules out of the served CSS, so the CSP's inline-style allowance is not newly relied upon.
  **T-35-SC: no package-manager install anywhere in this plan**, so the Package Legitimacy Gate
  correctly did not run rather than being skipped.
- **Falsifiable evidence + runtime parity — the arm inventory.** Fifteen recorded directions:
  the mobile-diff CONTROL 0 (self-diff), the opening and closing clean arms, ARM F1 (sub-`lg` rule
  surfaced), ARM F2 (above-`lg` rule dropped and relocated), the 21-test pre-change RED arm and its
  right-reason page-snapshot check, the 21-test GREEN arm ×2, the T-35-13 probe's two non-vacuity
  websocket gates, its board-changed poll, its pre-change fail arm, the jsdom `getScroll` measurement
  on a deliberately overflowing element, the `sm:max-w-md` control behind the two absence claims, and
  the `max-width:9999px` absent-probe control on the served stylesheet. Runtime parity is not merely
  respected but **enforced twice**, the second time against this plan's own commit.

---

## Deviations from Plan

### 1. [Rule 1 — Bug] Two test-file comments were generating live CSS rules

Full account in (ii). Found by the diff this task exists to run; not present at the merge base; not
applied by any shipped element; fixed following the branch's own precedent `34256f5c`. Commit
`59b80ce0`.

### 2. [Rule 3 — Blocking] The rebuild needs two `sync-runtime` passes, twice over

`docker compose up -d --build --force-recreate frontend` rebuilds the **core-java dependency image**
and only *starts* its container, leaving `[container-not-recreated]`. Reproduced identically on both
rebuilds in this plan. Not a defect in the gate — the gate is what caught it — but it means one
`sync-runtime` invocation cannot converge when a stale service has a built dependency. Recorded
rather than fixed: `scripts/sync-runtime.sh` is outside this plan's declared file set.

### 3. [Deviation from the plan's instruction — no duplicate filed] The coverage-gap issue already exists

The plan says "File a GitHub issue". **#689 already exists and already states everything the plan
requires.** Filing a second would split the record — itself a defect. Commented on #689 with this
plan's new browser-side evidence instead. Recorded rather than silently substituted.

### 4. [Instrument defect, self-caught ×3]

- `NODE_ENV=production` overrode `--minify=false`, minifying the stylesheet to one line so every
  line-anchored probe read 0. **No conclusion was drawn**; regenerated with `env -u NODE_ENV`.
- `npx jest app/dashboard/orders/[id]/...` — the bracketed route segment is a **regex character
  class**, so only one of two named suites ran while the summary read "1 passed, 1 total". Re-run
  with `--runTestsByPath`: 2 suites / 52 tests.
- Turbopack refuses a `node_modules` symlink pointing outside the project root
  (`Symlink ... is invalid, it points out of the filesystem root`). Replaced with a real copy, with
  inode identity and link counts printed to rule out 35-09's hardlink-contamination trap.
- The pre-change server's teardown killed the `npx` wrapper, not the server. Resolved the real PID
  from the **listening port** via `ss -ltnp` — never `pkill -f`, whose pattern would have matched
  the enclosing shell's own command line.

### 5. [Observed, not caused] `marketing-motion` timeouts

Attributed to pre-existing **#687** with six independent lines of evidence in (iv). Not adjusted
away; the new symptom set was added to that issue.

### 6. [Recorded, NOT fixed] The skip budget is 7/6 with one undeclared skip

Unchanged by this phase and unchanged by reseeding. Reported honestly rather than absorbed by a new
`ALLOW`. This is **#686**, whose gate is itself wired only into the dark lane (**#683**).

### 7. [Recorded] Synthetic orders left on the dev stack

The T-35-13 probe and `seed-order-metric.sh` placed a handful of real guest orders, each labelled in
its own `notes` field as synthetic and safe to delete (`T-35-13 Live Reflow Probe`,
`Metric Seed Probe`). They are visible in the captures. Local dev stack only.

---

## Known Stubs

None. This plan ships no placeholder, no empty state, no mock data source, no TODO and no
hardcoded empty value. The two files it edits are comment-only changes to existing test suites.

## Threat Flags

None. No endpoint, input, credential, data flow or dependency was added.

| Threat | Outcome |
|---|---|
| **T-35-45** a stale runtime | **mitigated, and it was REAL.** The delivered container was serving the merge-base stylesheet, proven by content and by chunk identity. Rebuilt; 4/4 FRESH, 0 unverified; tier values read out of the served stylesheet, not the host filesystem. Enforced a second time against this plan's own commit |
| **T-35-46** a branch behind its base | **mitigated.** `check-branch-behind-base.sh` rc=0 at start and close — 67 ahead, 0 behind |
| **T-35-47** a diff comparing a file to itself | **mitigated, and better than planned.** CONTROL 0 plus ARM F1/F2, which arm the *filter* rather than only the diff — the failure mode a self-diff cannot catch |
| **T-35-48** the vacuous accessibility pass | **mitigated, and the finding is sharper than the register anticipated.** The jsdom blindness was measured first-hand; the browser sweep was then measured to be green for the *same structural reason* on this seed, and recorded as report-only rather than as a gate |
| **T-35-49** KDS reflow under a live feed | **mitigated, and the named instruments were measured NOT to exercise it.** Driven for real over a live websocket with non-vacuity gates and a pre-change fail arm |
| **T-35-49b** claiming nightly cover that does not exist | **mitigated.** Recorded as "covered by a spec that no current tree executes", citing #683; this plan's runs are named a one-off observation |
| **T-35-SC** package installs | **accepted.** None performed |

## Cited decisions

**ORCH-01, ORCH-02, ORCH-03, ORCH-04, ORCH-05 and ORCH-06 (orchestrator decisions, 2026-08-29,
CONTEXT.md §4b)** — cited by that name throughout, never as user decisions. ORCH-02's desktop CLS
arm and ORCH-04's landing widening are the two this plan measures directly on the delivered runtime;
ORCH-03's marker is what every tier query in this plan resolves against; ORCH-06's policy band is
the reason the prose-measure count moved and is recorded in the ledger as a betterment.

## Commits

| Commit | Type | Subject |
|---|---|---|
| `59b80ce0` | fix | stop two test-file comments generating live CSS rules nobody applies |

Every message was written through a **quoted heredoc** and passed with `git commit -F`, never an
interpolating `-m` string, then read back with `git log -1 --format=%B` — backticks inside a
double-quoted message execute and are silently dropped, and the corruption is invisible at write
time. Both commits stage **named paths only**; `git diff --diff-filter=D HEAD~1 HEAD` reports
**zero deletions**.

## TDD Gate Compliance

Not a `type: tdd` plan, and correctly so: the deliverable is evidence, not behaviour. The one code
change is a comment edit whose *fail direction was the finding itself* — the two rules were observed
present in the emitted stylesheet before the change and observed absent after, in both the locally
generated stylesheet and the delivered artefact, with a control (`sm:max-w-md`) proving the absence
is about the fix rather than about the search.

## Next

Plan **35-13** is the owner gate. Three things are flagged for it explicitly:

1. **ORCH-01** — public `/shop` deliberately stays at Marketing (1280px); CONTEXT.md marks this
   "owner-visible if wrong", so it wants an explicit look rather than a silent inheritance.
2. **The −20px at 1440** on the three Detail surfaces (1120 → 1100), which 35-05 flagged because
   1440 is the width the owner was told would not move. The remedy if rejected is `DETAIL_MAX_PX`,
   one number in one file — **not** untiering the surfaces, which would hand them the 1600–1636px
   Shell band instead.
3. **The eight captures** above, for the before/after look at 1920 and 2560.

Two open issues remain untouched and are not this phase's: **#686** (skip budget 7/6) and
**#683** (the dark nightly lane), plus **#687** and **#689**, both of which now carry this plan's
measurements.

## Self-Check: PASSED

Run with a control in the failing direction on **both** halves, so a FOUND result is a statement
about this repository rather than about a check incapable of failing:

```
FILES    35-12-SUMMARY.md                                   FOUND
         kitchen/__tests__/page.test.tsx                    FOUND
         orders/[id]/__tests__/detail-tier.test.tsx         FOUND
         4 sampled captures under e2e-artifacts/35-12/      FOUND   (8 png total, as claimed)
         scripts/check-runtime-freshness.sh                 FOUND
         CONTROL 35-12-DOES-NOT-EXIST.md                    MISSING (control OK)

COMMITS  59b80ce0 (this plan)  34256f5c (the cited precedent)  96c8d794 (the merge base)   FOUND
         CONTROL deadbee                                                                   MISSING

CLEANUP  frontend/e2e/tmp-3512-kds-live-reflow.spec.ts      absent
         'tmp-3512' anywhere under frontend/e2e             0 file(s)
```
