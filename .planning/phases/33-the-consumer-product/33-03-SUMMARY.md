---
phase: 33-the-consumer-product
plan: 03
subsystem: ui
tags: [landing, seo, json-ld, permissions-policy, geolocation, cwv, playwright, server-component]

requires:
  - phase: 33-the-consumer-product
    provides: "33-00's CA-2 (geolocation denied at the header) and CA-3/CA-4 (the invented vendors and the four 'near you' sites)"
provides:
  - "The landing kitchen row renders the real published shops, server-side, in the initial HTML"
  - "Permissions-Policy permits geolocation to same-origin — 33-07's located path is no longer dead on arrival"
  - "shopListStructuredData JSON-LD on /, asserted against the raw response bytes"
  - "frontend/e2e/perf-budgets.ts — the repo's first declared CWV budget"
  - "A recorded / client-JS baseline (953,353 bytes) for 33-07 to be measured against"
affects: [33-07]

tech-stack:
  added: []
  patterns:
    - "An unsatisfiable criterion is replaced with a scoped one AND a control that proves it was scoped, not narrowed"
    - "Locate by role, never by attribute selector, on any streamed page"
    - "A pre-existing defect is recorded against a measured control arm rather than budgeted away"

key-files:
  created:
    - frontend/components/marketing/shop-card.tsx
    - frontend/components/marketing/__tests__/shop-card.test.tsx
    - frontend/e2e/perf-budgets.ts
    - frontend/e2e/landing-webperf.spec.ts
  modified:
    - frontend/next.config.mjs
    - frontend/app/page.tsx
    - frontend/app/__tests__/landing.test.tsx
    - frontend/e2e/storefront-ssr-seo.spec.ts
    - frontend/e2e/marketing-dish-scroller.spec.ts
    - frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap
    - docs/metrics.json

key-decisions:
  - "The 'near you' criterion is scoped to HEADING elements; the CTA at page.tsx:133 and the Browse step body at :25 are deliberately OUT of scope and the judgement is recorded, not hidden"
  - "CLS_BUDGET was NOT raised to 0.2 to make / green — the spec asserts no-regression against a measured pre-existing 0.1793 and annotates the unmet target"
  - "The DishScroller label is byte-identical; the SPEC changed how it locates it, from an attribute selector to getByRole"
  - "The scroller affordance assertion is re-stated as an invariant (disclosure matches actual overflow) rather than the old constant, which three cards legitimately invert"

patterns-established:
  - "Validate a test instrument on the CLEAN tree before trusting any break arm it reports"
  - "Run the WHOLE unit suite: a green tsc and a green E2E run cannot see a broken jest suite"

requirements-completed: [CUST-01]

duration: 2h
completed: 2026-08-08
---

# Phase 33 Plan 03: The Landing Page Tells the Truth — Summary

**Five invented vendors replaced by three real published shops in the server-rendered HTML, geolocation un-denied at the header, JSON-LD emitted for the first time — and an unsatisfiable criterion replaced with a scoped one that carries its own proof of scoping.**

## Performance

- **Duration:** ~2h (including a human gate and three container rebuilds for break arms)
- **Tasks:** 5 of 5 (Task 4 was a blocking human gate — approved)
- **Files:** 4 created, 7 modified

## Task Commits

1. **Tasks 1–2: geolocation + the real row** — `64e2cd1e` (feat)
2. **Task 3: CWV budget** — `a9b2a361` (feat)
3. **Task 4: human gate** — approved by the owner; no commit of its own
4. **Task 5: scoped criterion + JSON-LD** — `ad14848f` (feat)
5. **Task 5 fixes: three regressions + metrics** — `380ba3b8` (fix)

## Verified against the delivered runtime, not the source

The frontend was rebuilt and **force-recreated** four times across this plan (a rebuild that is only `start`ed serves the old code). Running container image id matched the tag's id each time. Final live reads:

```
Permissions-Policy: camera=(), microphone=(), geolocation=(self), browsing-topics=()

occurrences in the served bytes of /        (counted with awk, not `grep -c`,
                                             which counts LINES and this HTML is one line)
  Mama Ade 6 · Peckham Jollof 6 · Brixton Village 8
  application/ld+json 2 · ItemList 2 · numberOfItems 3
  Mama's Kitchen 0 · Spice Route 0 · Olive & Vine 0 · Crumb & Co 0 · Hanoi House 0 · FHRS 0
  CONTROL "Kitchens on J" 2
```

## The criterion that could not be satisfied, and how it was replaced

The original read: *"the string 'near you' is absent from the landing DOM"*. `/` renders it at **four** sites and three are legitimate — the primary customer CTA (protected by two existing tests), the Browse step body, and the scroller's `aria-label` (which **is** another spec's selector). No document-wide absence assertion can ever pass.

Replaced with a **heading-scoped** form, and the in/out-of-scope judgement written into the spec rather than left implicit. `:133` and `:25` are aspirational copy about what the platform is *for*, not claims about the current result set; rewriting the main CTA under a data-truthfulness criterion would exceed this phase's mandate, so it is **escalated as a copy decision, not absorbed**.

**The control that distinguishes scoping from narrowing** is asserted explicitly in both the served-HTML and DOM tests: `:133` and `:25` must still be present. Without it, a criterion narrowed until green looks identical to one correctly scoped.

## Falsification — every criterion run in the fail direction

Break arms ran against **rebuilt containers**, because the header and the row are both build outputs. `page.tsx` restored by content each time (`git hash-object` = `326d3c43…`).

| Arm | Result | Verdict |
|---|---|---|
| clean first | 35/35 | 0 |
| restore the `:180` lying heading | served-HTML heading assertion **failed**; DOM `getByRole(...).toHaveCount(0)` **failed** | caught |
| …and the scoping control in the same run | `Order food near you` 2, `Find independent kitchens` 2 — **still served, did not trip the criterion** | proves scoping, not narrowing |
| remove the `ld+json` script | JSON-LD assertion **failed** | caught |
| `ld+json` present but a **well-formed EMPTY** ItemList | `the ItemList is well-formed but empty` — and **2 `ld+json` blocks were present**, so a tag-exists check would have passed | caught |
| the 14 unrelated tests, during the breaks | **passed** | breaks were targeted, not general breakage |
| clean last, full rebuild | **41/41** playwright, **850/850** jest across 95 suites | 0 |

## An instrument I built, checked, and threw away

To avoid three container rebuilds I tried serving the production build locally on `:3002`. **I validated it on the clean tree first — and it failed my three tests there**, serving 29 bytes. Had I skipped that check and run the break arms against it, every arm would have "failed" for the wrong reason and read as a spectacular success.

That validation step is the difference between a control arm and a fabrication. The docker path was slower and was used instead.

## The CLS finding: real, and not this phase's

`/` measures **CLS 0.1793** and fails the 0.1 budget. Established by **building the pre-change commit `8f6c03b1` and running it simultaneously on `:3001`** under identical throttling:

```
CONTROL   pre-33-03   CLS=0.1793  LCP=764ms  shifts=1
TREATMENT 33-03       CLS=0.1793  LCP=744ms  shifts=1
```

Identical to four decimal places. The single shift fires at ~1516 ms with `sources` entirely in the hero — search form, category chips, paragraph, both persona doors — i.e. client-island hydration **above** the row this plan rewrote. The control arm was itself validated before being trusted: it serves the invented `Mama's Kitchen`, the old heading, and the pre-fix `geolocation=()` header.

**`CLS_BUDGET` was not raised to 0.2 to go green.** The spec asserts no-regression against the recorded value — which fires if 33-07's island makes it worse — while the absolute 0.1 target stays declared and unmet, annotated on every run so the debt stays visible. Fixing it means changing how `HeroSearch` hydrates: outside this plan's file set. **Escalated, not absorbed.**

## Five defects in my own instruments, every one caught by a guard rather than by review

1. **The bundle meter read `content-length`** and reported **zero** — Next serves chunks without that header. It would have sailed under any ceiling. Caught only by the non-vacuity assertion.
2. **An invented baseline of 461,000 bytes** in `perf-budgets.ts` — the "declare a constant nothing consumes" shape. Real figure: **953,353 bytes / 21 scripts** (control 945,338 / 20; the +8,015 is `ShopCard`).
3. **The `[aria-label="…"]` selector matched the streaming staging buffer's hidden copy** — 2 elements, strict-mode violation. Removing `networkidle` (required, because 33-07 holds a request open) exposed a race the old spec was surviving by accident. **The wait was not redundant; it was masking a fragile locator.** Now `getByRole`, with the arrows scoped to the live region too.
4. **In my own new heading test I hit the same trap three lines below a comment describing it**, and separately asserted visibility on `Reveal` content without scrolling — the recorded "scroll-reveal reads as an empty band" mistake.
5. **`headers()` outside a request scope** broke all eight rendering tests in `landing.test.tsx`, while `npm run build` (rc=0, zero type errors) and 41 Playwright tests were green over it.

## Deviations from Plan

**[Rule 1 - Bug] `headers()` broke the unit suite** — Found during: Task 5 clean-last sweep | Fix: mocked `next/headers` to an empty Map, the honest no-nonce path | Commit: `380ba3b8`

**[Rule 1 - Bug] A GitHub issue reference read as a hex colour** — `palette-discipline` greps `components/marketing` for `/#[0-9a-fA-F]{3,8}/`; `544` is valid hex, so two comments scored 2 against an expected 0. The convention already existed (`dish-scroller.tsx` writes "PR 221"). Reworded to "issue 544" with a note. **The gate is right about hex and was left alone.** | Commit: `380ba3b8`

**[Rule 1 - Bug] The security-headers snapshot fired on the intended header change** — read the diff (one line, exactly the intended change), updated with `-u`. A regression guard firing on a real change is working. | Commit: `380ba3b8`

**[Rule 3 - Minor] The scroller spec was re-stated** — three cards do not overflow at 1440px (measured: 390px `canRight=true`; 1440px both false), so the old constant assertion would fail while the component behaves exactly as its docblock promises. Re-stated as the invariant that was always the intent — disclosure matches **actual** overflow, measured per run — which is strictly stronger: it now also catches a fade shown over a row with nothing behind it. **No assertion deleted.**

**Total deviations:** 4 (3 auto-fixed bugs, 1 spec re-statement). **Impact:** all found by guards; none changed the plan's scope.

## Verification Results

| Success criterion | Result |
|---|---|
| Row names the three real published shops, from the API at request time | **PASS** — 3 in the served bytes |
| Zero invented vendor names anywhere in the served HTML | **PASS** — 0 each, and 0 `FHRS`; control string reads 2 |
| No heading matches `/near you/i`, observed failing against a restored `:180` while `:133`/`:25` did not trip it | **PASS** — both directions run |
| The replacement of the unsatisfiable criterion is recorded with line numbers and the scope decision | **PASS** — in `storefront-ssr-seo.spec.ts` |
| Real names in the INITIAL HTML, asserted against raw bytes | **PASS** |
| `page.tsx` stays a Server Component; all `landing.test.tsx` tests pass, none deleted | **PASS** — 6 migrated/kept + 3 added = 9, all green |
| `Permissions-Policy` permits `geolocation=(self)` LIVE and denies the other three | **PASS** — live `curl` on the rebuilt container |
| `/` meets the declared throttled-mobile budget; bundle recorded | **LCP PASS (744 ms vs 8000).** **CLS 0.1793 does NOT meet the 0.1 target — pre-existing, proven by control arm, recorded not budgeted away.** Bundle baseline 953,353 bytes recorded in `perf-budgets.ts` |
| Every displaced good preserved or replaced; the row reads as deliberate at N=3 | **PASS** — enumerated in `page.tsx`'s docblock; **human-approved** at 390px and 1440px |
| `/` emits `shopListStructuredData` JSON-LD for the three real shops | **PASS** — `ItemList`, `numberOfItems: 3`, real names/URLs/addresses, asserted on raw bytes and rejecting an empty list |
| Both marketing specs green, `networkidle` removed, no assertion deleted | **PASS** — `networkidle` count 0 |

## Issues Encountered

- **`/` CLS 0.1793** — pre-existing, hero hydration, escalated. Needs its own scoped work on `HeroSearch`.
- **`scripts/check-doc-metrics.sh` is RED on this branch and that is by design.** `docs/metrics.json` was regenerated by script (2509 → 2528; jest 839→850, playwright specs 18→19) so gate 1 is green, but `AGENTS.md` still says 18 specs. **33-07 Task 4 owns the prose.** Do not hand-edit it — the figure is wrong again the moment the next plan lands.

## Next Plan Readiness

`33-04` is the remaining wave-2 plan and is **not started**. It is independent of this one (Keycloak customer-realm IdP groundwork + ADR-0005) and its owner decision is already taken (`q3-record`).

For `33-07`: the bundle ceiling must be justified against **953,353 bytes**, and the CLS no-regression assertion is already in place to catch its client island.
