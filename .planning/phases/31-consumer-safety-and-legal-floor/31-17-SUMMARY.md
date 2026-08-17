---
phase: 31-consumer-safety-and-legal-floor
plan: 17
subsystem: ui
tags: [nextjs, seo, sitemap, robots, playwright, jest, accessibility, legal, crawlability]

requires:
  - phase: 31-08
    provides: the /legal index shell, the policy-page component and the heading levels 31-03 set
  - phase: 31-11
    provides: /legal/privacy and /legal/cookies
  - phase: 31-12
    provides: /legal/retention, and the retention-fit tests already in public-layout.spec.ts
  - phase: 31-13
    provides: /legal/accessibility and its two-configuration contact fallback
provides:
  - A Legal column in PublicFooter linking all five policy routes as real crawlable anchors
  - Reachability on every public route INCLUDING the tenant storefront, via the inherited footer
  - The five legal routes in sitemap.ts, verified not disallowed by robots.ts
  - Per-PR browser proof that each route returns 200 with unique, non-default, non-empty metadata
  - Per-PR browser proof that the five links resolve FROM A STOREFRONT, not only from the landing page
  - A regression pin on the platform trading disclosure staying off the shared footer
affects: [31-18, any future SEO audit, any future footer edit, QA council public-surface phase]

tech-stack:
  added: []
  patterns:
    - "Conditional grid columns must be LAST in DOM order; anything after one shifts a track when it unmounts"
    - "Assert reachability on rendered output, never on a source grep — the component's own comments name the routes"
    - "A break arm must be proven LIVE in the served artifact before it is measured, or it is VOID"
    - "it.each with a bare array-literal identifier is the only form both test counters agree on"

key-files:
  created:
    - frontend/components/public/__tests__/public-footer-legal.test.tsx
  modified:
    - frontend/components/public/public-footer.tsx
    - frontend/app/sitemap.ts
    - frontend/e2e/public-layout.spec.ts
    - docs/metrics.json

key-decisions:
  - "StorefrontLegalStrip NOT built — app/shop/layout.tsx:73 already renders PublicFooter over /shop/**, re-measured on a served storefront"
  - "Legal column placed THIRD, ahead of the conditional operator column, so it never shifts a grid track when a customer session resolves"
  - "Grid sm:grid-cols-3 -> sm:grid-cols-2 lg:grid-cols-4; track count stays fixed per breakpoint and is never derived from session state"
  - "Canonical labels reused verbatim rather than footer-shortened — two labels for one href is the #382 shape"
  - "The plan's 'no Companies House string / no company-number literal' criterion REPLACED: the first cannot fail, the second was already false on the clean tree"
  - "sitemap priority 0.3 / yearly — indexable but must not outrank a storefront"

patterns-established:
  - "Non-vacuity control before every absence assertion (a known-present link, the landmark, the section)"
  - "Company-identity assertions count EXACTLY ONE occurrence, so both deletion and duplication are failures"

requirements-completed: [LGL-01]

duration: 95min
completed: 2026-08-16
---

# Phase 31 Plan 17: Legal Reachability Summary

**Five published policy pages went from linked-by-nothing to reachable from a crawlable `<a href>` on every public route including the tenant storefront, proven in a browser against a runtime verified by content — and the plan's own tenant-storefront criterion was found unfalsifiable and replaced.**

## Performance

- **Duration:** ~95 min
- **Tasks:** 2/2
- **Files modified:** 6 (4 source/test, `docs/metrics.json` generated, `deferred-items.md`)

## Base verification — THE WORKTREE DEFECT HIT THIS EXECUTOR

The brief warned that wave-3 worktrees were branched from `main`. This one was:

| Check | Result |
|---|---|
| `git rev-parse HEAD` at spawn | `bb2ae65d` — **main**, not the intended `2e9a51fe` |
| `31-17-PLAN.md` present? | **No** — the reliable tell |
| `frontend/app/legal/privacy/page.tsx` present? | **No** |
| `git rev-list --count 2e9a51fe..HEAD` | **0** — nothing of my own to lose |
| `git merge-base --is-ancestor HEAD 2e9a51fe` | rc=0 — strict ancestor, so a fast-forward is safe |
| `git rev-list --count HEAD..2e9a51fe` | **118** commits behind |
| Working tree | clean |

Corrected with `git merge --ff-only 2e9a51fe` (160 files, +35477). HEAD is now `2e9a51fe` and all five legal routes plus the plan file are present. **Had this not been caught, the merge would have dragged Phase 28 into the phase branch and shipped a runtime missing waves 1–2.**

## Accomplishments

### Task 1 — the Legal column and the sitemap entries (`b791e1ca`)

`PublicFooter` gains a Legal column: an `<h2>` (matching what 31-03 set — an `<h3>` would reintroduce the `heading-order` node that plan closed) over five `<Link>`s rendering real `<a href>`.

**The plan's central measurement re-verified, not taken on trust.** `app/shop/layout.tsx:73` renders `<PublicFooter />` over the whole `/shop/**` subtree. Confirmed twice: by reading the file, and by fetching a served `/shop/test-kitchen` and finding all four new labels plus the OGL line in its HTML. `StorefrontLegalStrip` was therefore not built.

**A layout invariant the plan did not mention, and the reason for the column's position.** The footer's existing comment is emphatic that the track count must not change with session state, because the operator column unmounts after first paint. Adding a fourth column re-opens that: grid auto-flow pulls every item *after* a removed one forward. So the Legal column sits **third**, ahead of the conditional operator column — placed fourth it would jump a whole track the moment a customer's session resolved. Pinned by a test that renders both session states and asserts Legal's index is identical.

Grid went `sm:grid-cols-3` → `sm:grid-cols-2 lg:grid-cols-4`. Four columns in a 640px viewport is ~124px each and "Cookie and browser-storage policy" is not a 124px label; 2×2 at `sm`, one row at `lg`. The count is still fixed per breakpoint and never derived from session state.

**`robots.ts` was read, not assumed** — and then checked again against the *served* `/robots.txt`:

```
Disallow: /api/  /auth/  /dashboard  /shop/orders  /shop/signin
          /shop/auth/  /shop/*/cart  /shop/*/checkout  /shop/*/orders/
          /track  /unsubscribe
```

No `/legal` prefix appears. No robots change was needed.

### Task 2 — per-PR browser proof (`20fd06df`, `22923f22`)

Four new routes in `PUBLIC_ROUTES` (so each also gets the existing ratio/image/overflow sweep) and three new tests, all stack-free. **No `ci-cd.yaml` edit** — confirmed at `ci-cd.yaml:321` that `npx playwright test e2e/public-layout.spec.ts` is already the per-PR run line, and `git diff` lists 0 changes to it.

## The served evidence, captured for 31-18

Read off `http://localhost:3117` serving **this** worktree (identity proven below), so a future SEO check can compare against a real capture rather than a claim.

| Route | Served `<title>` | Canonical |
|---|---|---|
| `/legal` | `Legal & company information — J'Toye` | `/legal` |
| `/legal/privacy` | `Privacy notice — J'Toye` | `/legal/privacy` |
| `/legal/cookies` | `Cookie and browser-storage policy — J'Toye` | `/legal/cookies` |
| `/legal/retention` | `Data retention schedule — J'Toye` | `/legal/retention` |
| `/legal/accessibility` | `Accessibility statement — J'Toye` | `/legal/accessibility` |

Served meta descriptions (all five distinct, none the root default):

- `/legal` — "Company registration and legal information for J'Toye Digital Ltd, the operator of the J'Toye platform."
- `/legal/privacy` — "How J'Toye and the vendors on it use your personal data, who is responsible for what, and how to exercise your rights."
- `/legal/cookies` — "Every cookie and item of browser storage J'Toye uses, what it does, and how long it lasts."
- `/legal/retention` — "How long J'Toye keeps each category of data, the lawful basis, and whether the period is enforced automatically."
- `/legal/accessibility` — "J'Toye's WCAG 2.1 AA conformance status, known exceptions and how to report an accessibility problem."

`sitemap.xml` carries all five (1 occurrence each, verified on the served document).

**The accessibility contact FELL BACK, and that is what is being asserted.** `NEXT_PUBLIC_DATA_PROTECTION_EMAIL` is a build arg and is unset in a stack-free run, so `resolveControllerContact().anyRoute` is false and 31-13's documented fallback renders — naming `/legal/privacy` and `/legal` rather than emitting an empty `mailto:`. The test follows every internal href the section publishes and requires 200 from each, so it covers the configured branch too without needing one.

## Did the browser assertions actually run, and against what?

**Yes — and NOT against port 3000.** `:3000` is the compose `jtoye-frontend` container, image `LastTagTime=2026-08-10`. Measured before trusting anything:

| Route on `:3000` | Status |
|---|---|
| `/legal` | **200** |
| `/legal/privacy` | **404** |
| `/legal/cookies` | **404** |
| `/legal/retention` | **404** |
| `/legal/accessibility` | **404** |

Its footer contained none of the four new labels. **A naive "is `/legal` reachable" check against `:3000` would have returned 200 and passed.** That is the trap 31-12 hit, in the same place.

So this worktree was built and served on **:3117** (verified free first), and its identity proven by content before any test result was believed: all five routes 200, all four new labels present in the footer of a served `/shop/test-kitchen`, sitemap listing five, OGL line intact. Playwright then ran with `PLAYWRIGHT_BASE_URL=http://localhost:3117`: **38/38 on both `mobile` and `desktop`**, including 31-12's two retention tests unmodified. Only the PID group this run started was ever stopped.

## Fail-direction arms — both directions, every arm

### Task 1 (jest). Bracket: clean → A,B,C,D → clean. Footer restored and verified by `git hash-object` after every arm.

| Arm | Break | Result |
|---|---|---|
| 0 | none | **16/16 pass**, hash `b1e6d988` |
| A | drop the `/legal/cookies` `<li>` | **RED, 3 tests.** `✕ links /legal/cookies with a crawlable anchor` — **names the route**. Restore OK |
| B | mount `CompanyLegalLine` in the footer | **RED, 3 tests.** `not.toMatch(/is a company registered in/i)` fired; company-number count `Expected: 1, Received: 2`. Restore OK |
| C | Legal heading → `<h3>` | **RED, 4 tests.** level-2 count `Expected: >= 3, Received: 2`. Restore OK |
| D | delete the OGL attribution block | geo gate **rc=1** + 3 attribution tests RED. Restore OK |
| E | clean again | **16/16 pass**, hash `b1e6d988` — restores held |

**Arm D corrects a plan prediction.** The plan expected `check-geo-attribution.sh` to exit **2 (VOID)**. It exited **1 (FAIL)**, with `FAIL: the footer does not name 3 required rights holder(s)`. Exit 2 requires the gate to be unable to *find* the footer; deleting the attribution block leaves the footer findable. Both are non-zero and both are treated as failure here, but the distinction is recorded rather than glossed.

**Arm B is the whole reason the criterion was replaced.** Its captured `textContent` shows `CompanyLegalLine` rendering `J'Toye Digital Ltd is a company registered in England & Wales (company no. 16471464)` — containing **no "Companies House" string at all**. The plan's assertion would have printed a PASS on its own break arm.

### Task 2 (Playwright, against a rebuilt runtime)

**The first attempt was VOID and is reported as such.** `nohup npx next start &` records the PID of `npx`, not the `next-server` child, so `kill $!` left the server running: every "rebuild" wrote `.next` under a live process that went on serving the old routes. Arms A, B and D printed **PASS having measured nothing**, and arm C's failure (`no contentinfo landmark`) was `.next` being rewritten underneath the server, not the assertion firing — **three false passes and one false failure, all from the harness.** The tell was `WARN: :3117 still answering` before every arm.

Rebuilt with `setsid` + process-group kill, a stop confirmed by the port *refusing*, and — the fix that matters — **a check that the break is visible in the SERVED HTML before anything is measured.** An arm that cannot show its break is reported VOID, never as a pass.

| Arm | Break | Break live? | Result |
|---|---|---|---|
| 0 | none | — | 3/3 pass |
| A | `/legal/cookies` title duplicates `/legal/privacy` | `<title>Privacy notice` present in served `/legal/cookies` | **RED:** `two policy pages share a <title>: [... "Privacy notice — J'Toye","Privacy notice — J'Toye" ...]  Expected: 5  Received: 4` |
| B | `/legal/retention` description blanked | first run **VOID** (stale `.next`); after `rm -rf .next`, served meta = `[]` | **RED:** see below |
| C | remove the `/legal/accessibility` footer link | `href="/legal/accessibility"` gone from served `/shop/test-kitchen` | **RED:** `/ footer does not link /legal/accessibility` |
| D | contact points at a nonexistent path | the bad path present in served HTML | **RED:** `the feedback section links /legal/privacy-notice-that-does-not-exist, which does not resolve  Expected: 200  Received: 404` |
| E | clean again | — | 3/3 pass; all four target files hash-identical; `git status` empty |

## Deviations from Plan

### Auto-fixed

**1. [Rule 3 – Blocking] jest `expect` takes one argument**

- **Found during:** Task 1. 16 tests red with `Expect takes at most one argument`.
- **Cause:** the second-argument message form is Playwright's, not jest's.
- **Fix:** intent moved into test names and comments.
- **Commit:** `b791e1ca`

**2. [Rule 1 – Bug] a missing meta tag failed by 60s timeout instead of naming the route**

- **Found during:** Task 2 arm B — **only** by running the fail direction.
- **Issue:** Next omits `<meta name="description">` entirely for an empty description. `locator.getAttribute()` **waits**, so the test blocked for the full 60s timeout and failed with `locator.getAttribute: Test timeout exceeded` — naming neither route nor tag, and never reaching the explicit non-emptiness assertion written for exactly this case. **The test was red either way, which is why nothing else would have caught it: a red test looks like a working test. The failure MESSAGE was the defect.**
- **Fix:** read through a `count()` check, so absence becomes `""`. Now fires in 1.1s with `/legal/retention meta description is empty`.
- **Commit:** `22923f22`

**3. [Rule 3 – Blocking] `it(` in a for-loop makes `docs/metrics.json` unsatisfiable**

- **Found during:** post-task gates. `docs-freshness.sh` exited **2 (VOID)**, naming the file, the line and the remedy.
- **Issue:** a loop is one declaration site and N executed tests; `docs-freshness.sh` counts sites and `check-test-count-oracle.sh` counts executions, and both are required.
- **Fix:** `it.each`. The first attempt — `it.each([...LEGAL_ROUTES])` — still failed the oracle by exactly 4, because the counter resolves an `.each` table only when it is an array literal declared in the same file and a spread is not one. Binding the bare name fixed it.
- **Commit:** `a75928f6`

### Deviations from the plan as written

**4. The tenant-storefront criterion was REPLACED, and both forms are recorded.**

The plan asked for "no 'Companies House' string and no company-number literal". Measured before being trusted; **neither half works**:

- `"Companies House"` is **0** in the footer — and **0** in `CompanyLegalLine` too, which says "is a company registered in … (company no. …)". The plan's own break arm cannot fire it. `0 == 0` in both directions.
- `"no company-number literal"` was **already false on the clean tree**: `public-footer.tsx:189` has carried `company no. 16471464` since `e484b96a` (PR #232), predating this phase.

Replaced with two assertions falsifiable in both directions, proven by arm B: `CompanyLegalLine`'s unique prose must be absent (0 clean → 1 on the break), and the company number must appear **exactly once** (0 = the Companies Act copyright line was deleted; 2 = a second disclosure was added). A third assertion is scoped to the Legal column alone, so it fails for *this* change rather than for the pre-existing line.

**5. Task 1's `<verify>` block's grep assertions are kept but demoted.**

They are run and recorded, but the load-bearing assertions are in the DOM. `public-footer.tsx` mentions `/legal/privacy` **twice in its own comments**; a grep over it passes with every link deleted. This is the ninth-and-tenth instance of the comment-satisfies-grep shape the brief flags.

## OWNER QUESTION — not answered here

**`PublicFooter` renders the platform's registered-company identity on every tenant storefront, and `lib/company.ts` says it must not.**

`lib/company.ts:9-12`, verbatim:

> this identity must only ever render on platform-owned surfaces (dashboard, sign-in, `/legal`) — never on tenant storefronts.

`public-footer.tsx:189` renders:

> `© 2026 J'Toye Digital Ltd · Registered in England & Wales · company no. 16471464`

and that footer is mounted by `app/shop/layout.tsx:73` over `/shop/**`. Confirmed in served HTML.

It is **pre-existing** (PR #232, before this phase), **hard-coded** rather than routed through `getCompanyInfo()`, and **out of this plan's scope**, so it was not touched. It is raised because it is a legal-content decision, not an implementation detail, and because the plan's criterion was written on the assumption it was already satisfied.

Both readings are defensible and I am not the right decider:

- **It should go** — a vendor's storefront should carry the vendor's trading disclosure, not the platform's, exactly as `lib/company.ts` states.
- **It should stay** — J'Toye operates the storefront as an intermediary, and UK trading-disclosure rules arguably require the operator to identify itself on pages it serves. Deleting it is a regression, not a fix.

Whichever way it goes, the current state is inconsistent with its own documented rule. The test added here **pins the count at exactly one**, so the decision is not silently pre-empted in either direction.

## Merge gates for the orchestrator

1. **`check-doc-metrics.sh` and `check-claims.sh` are rc=1 by design.** `docs/metrics.json` was regenerated with `docs-freshness.sh --write`; the prose in `README.md`, `AGENTS.md` and `CLAUDE.md` is outside this plan's scope and untouched (verified: 0 changed files). Reconcile to:

   | Key | Prose says | metrics.json |
   |---|---|---|
   | `jest_blocks` | 1214 | **1230** |
   | `jest_files` | 119 | **120** |
   | `playwright_blocks` | 104 | **107** |
   | `total_logical_invocations` | 3160 | **3179** |

   **Never by arithmetic** — re-run `docs-freshness.sh --write` on the merged tree.

2. `mcp-server/node_modules` had to be installed for `check-test-count-oracle.sh` to give a verdict rather than VOID. All three runners agree afterwards.

3. Nothing else. `ci-cd.yaml`, `frontend/app/legal/**`, `README.md`, `CLAUDE.md` and `AGENTS.md` are all provably unmodified.

## Verification results

| Check | Result |
|---|---|
| `npx jest` (full) | **120 suites / 1230 tests / 0 failures** (base 119/1214) |
| `npx playwright test e2e/public-layout.spec.ts` | **38 passed**, mobile + desktop, against :3117 |
| `npm run build` | rc=0, all five legal routes emitted |
| `npm run lint` | rc=0 (28 warnings, 0 errors — unchanged from base) |
| `scripts/check-geo-attribution.sh` | **rc=0** (not 2) |
| `scripts/docs-freshness.sh` | rc=0 |
| `scripts/check-test-count-oracle.sh` | rc=0, all three runners agree |
| `scripts/check-doc-metrics.sh` | rc=1 — **expected**, merge gate 1 |
| `scripts/check-claims.sh` | rc=1 — **expected**, merge gate 1 |
| `npx tsc --noEmit` | rc=2, 3 errors, **all pre-existing and none in this plan's files** (deferred item 1) |
| `ci-cd.yaml` modified? | **No** |

## Known Stubs

None.

## Threat Flags

None. No new network endpoint, auth path, file access or schema change. `T-31-17-02`'s mitigation was strengthened rather than weakened, and the pre-existing surface it touches is raised as an owner question above rather than silently altered.

## Self-Check: PASSED

All seven claimed files exist on disk. All four claimed commits resolve:

| Hash | Subject |
|---|---|
| `b791e1ca` | `feat(31-17): the Legal column makes five published policies reachable` |
| `20fd06df` | `test(31-17): per-PR browser proof that every policy route resolves and is reachable` |
| `22923f22` | `fix(31-17): a missing meta tag must name the route, not time out` |
| `a75928f6` | `chore(31-17): count the parameterised tests the way both counters count` |

Base correction confirmed: `2e9a51fe` is an ancestor of HEAD (it was not at spawn), and `bb2ae65d` remains an ancestor, which is what a fast-forward from main to the phase branch looks like.
