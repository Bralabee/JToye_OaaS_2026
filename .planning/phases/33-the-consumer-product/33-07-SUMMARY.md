---
phase: 33-the-consumer-product
plan: 07
subsystem: ui
tags: [geolocation, client-island, cwv, bundle-budget, playwright, disclosure, miles, requirements-closure, control-arm]

requires:
  - phase: 33-the-consumer-product
    provides: "33-03's real-shop server-rendered row, the Permissions-Policy geolocation=(self) fix, and the declared CWV budget + client-JS baseline"
  - phase: 33-the-consumer-product
    provides: "33-05's populated shops.latitude/longitude on the live database, closed against the delivered runtime"
  - phase: 33-the-consumer-product
    provides: "33-06's GET /public/shops?lat=&lon=&radiusKm= and the nullable distanceKm field"
provides:
  - "frontend/components/marketing/near-you-row.tsx — the gesture-gated client island: three states, heading derived from state, exclusion disclosed"
  - "frontend/lib/distance.ts — the single kilometres-to-miles conversion the customer-facing surfaces render through"
  - "frontend/e2e/near-you-row.spec.ts — the repo's first use of Playwright geolocation emulation, four arms"
  - "A post-grant CWV arm and an absolute, consumed client-bundle ceiling for the landing route"
  - "CUST-01 closed with per-limb evidence; CUST-02 and CUST-04 recorded honestly as not closed"
affects: [34-truthfulness, qa-council]

tech-stack:
  added: []
  patterns:
    - "Playwright geolocation emulation (grantPermissions + setGeolocation) makes both the granted AND the denied path deterministic — the failure path is reachable, not simulated"
    - "A unit conversion belongs in ONE module so a break arm on the constant is decisive; a test that derives its expectation from that constant passes for every value of it, including 1"
    - "A shift budget that would forbid the feature is replaced, not raised: the post-grant bound is VERTICAL pixels, because the horizontal movement IS the reorder the visitor asked for"
    - "An absolute bundle ceiling replaces a growth MULTIPLIER, which ratchets — each plan measuring against the last plan's total lets a route gain 50% three times and never fail"

key-files:
  created:
    - frontend/components/marketing/near-you-row.tsx
    - frontend/components/marketing/__tests__/near-you-row.test.tsx
    - frontend/e2e/near-you-row.spec.ts
    - frontend/lib/distance.ts
    - frontend/lib/__tests__/distance.test.ts
  modified:
    - frontend/app/page.tsx
    - frontend/types/storefront.ts
    - frontend/components/marketing/shop-card.tsx
    - frontend/e2e/perf-budgets.ts
    - frontend/e2e/landing-webperf.spec.ts
    - .planning/REQUIREMENTS.md
    - README.md
    - CLAUDE.md
    - AGENTS.md
    - docs/metrics.json

key-decisions:
  - "The customer reads MILES; the wire stays kilometres. radiusKm, distanceKm, jtoye.geo.* and 33-06's committed OpenAPI snapshot are untouched — conversion happens at render"
  - "The radius is quoted as '3.1 miles', not a tidier '3 miles': 3 miles is 4.83 km, a radius nothing applied"
  - "The exclusion count is computed from the shops themselves, never as (server count - located count), because subtraction reports a shop 20 km down the road as having no location data"
  - "fetch, not the axios publicApiClient next door — measured at 46,846 bytes of HTTP client on the LCP-critical route to issue one GET"
  - "The coordinate is held in React state only: no cookie, no storage of any kind, because issue 116's consent banner has not shipped"
  - "CUST-02 stays unticked and the gap is written into both REQUIREMENTS.md and this summary"

patterns-established:
  - "Pair every absence assertion with a positive control that proves the query can find the thing — a spinner test that finds one while the fix is outstanding is what makes 'no spinner' evidence"
  - "A control arm can itself be the defect: `p:visible` measured 1 on mobile and 0 on desktop, and would have reported deliberately out-of-scope copy as deleted on every desktop run"
  - "Restore break arms from a scratchpad copy, never `git checkout`, when the file holds uncommitted work — checkout restores from the index and eats it"

requirements-completed: [CUST-01]

duration: 3h 20m
completed: 2026-08-09
---

# Phase 33 Plan 07: The Located Journey — Summary

**A customer who taps "Use my location" now sees the real published kitchens reordered by a distance PostgreSQL computed, in miles, under a heading that only claims proximity when a coordinate is genuinely held — and the shops the filter removed are named rather than silently dropped.**

```
  no coordinate (initial, and after a denial)   "Kitchens on J'Toye"          3 real shops
  coordinate held, results inside the radius    "Kitchens near you"           reordered, each with its distance
  coordinate held, nothing inside the radius    "No kitchens within 3.1
                                                 miles — here is everything
                                                 on J'Toye"                   full list, no false claim
```

CUST-01 is closed. This was link 6 of #460's chain and the first plan in the phase a customer can
observe end to end.

## Performance

- **Duration:** ~3h 20m across two agent sessions (a human-verification gate sits between them)
- **Tasks:** 4 of 4
- **Commits:** 5 (4 task commits + this record)
- **Files:** 5 created, 10 modified

## Task Commits

1. **Task 1: The client island** — `b41a9173` (feat)
2. **Task 2: Deterministic granted, denied, far-away and exclusion arms** — `d8a511e1` (test)
3. **Task 3: Human verification** — checkpoint, no commit (see the verdict below)
4. **Checkpoint correction: miles** — `159b135f` (fix)
5. **Task 4: Requirements closure + prose counts** — `7650f988` (docs)

## The checkpoint verdict, verbatim

> **"Walkthrough was a success"**

Approved, with corrections. The owner walked the full journey on the rebuilt runtime and returned
three things:

1. **Distances must be shown in miles**, not kilometres.
2. **The radius as presented to the customer must be in miles** too — the "nothing within …" copy
   and every other user-facing mention.
3. Two **search** findings, verified by the orchestrator against the live stack, outside this
   plan's scope, and filed rather than built with the owner's explicit agreement (*"File as issue,
   close phase"*).

Both corrections are shipped in `159b135f`. Both findings are recorded in `REQUIREMENTS.md` and
below.

## The miles correction

The API contract is **untouched and deliberately metric**: `radiusKm`, `distanceKm`, the
`jtoye.geo.*` config keys and `33-06`'s committed OpenAPI snapshot all stay exactly as they were.
A unit choice belonging to one surface has no business in a machine contract three callers read.
So the wire carries kilometres and the customer reads miles, converted at render by
`lib/distance.ts`.

| | before | after |
|---|---|---|
| Distance pill | `0.3 km` | `0.2 miles` |
| Out-of-radius heading | `No kitchens within 5 km` | `No kitchens within 3.1 miles` |
| Exclusion disclosure | `further than 5 km away` | `further than 3.1 miles away` |
| Request sent | `radiusKm=5` | `radiusKm=5` — unchanged |

**The radius reads "3.1 miles" rather than a tidier "3 miles" on purpose.** Three miles is 4.83 km,
which is not the radius that was applied, and no visitor should be told their results were filtered
by something they were not. The number is derived from the 5 km actually sent, through the same
function that formats the pills, so the copy cannot drift from the query. If a round number is ever
wanted there, the fix is to change the radius to one that converts cleanly and send that — not to
round the label. A comment in the source says so.

The plan's original truth — *"the distance shown is the same number the ordering used"* — now reads
*"is a unit conversion of it"*, which is a strictly weaker claim and is stated as such rather than
quietly kept. A conversion that reordered anything would be a defect, so `distance.test.ts` asserts
monotonicity directly; nothing is recomputed in the browser, before or after.

One decimal of a mile is **161 m**, which is coarser than the ~100 m postcode-centroid error in the
input. The figure therefore cannot imply more precision than the data has — the same reasoning that
kept the old format off metres, and slightly better satisfied by miles than by kilometres.

### The break arms on the conversion

Run against the committed tree, restores verified by `git hash-object`, clean direction asserted
**last**:

| Arm | Break | Result |
|---|---|---|
| The conversion is applied at all | `MILES_PER_KM = 1` | **12 tests red** across both suites. The rendered assertions fired too: the card printed `3.0 miles` for 3.0104 km and the disclosure said `further than 5.0 miles away` |
| Kilometres cannot reach the visible copy | radius text back to `${NEAR_YOU_RADIUS_KM} km` | **2 tests red** — the out-of-radius heading and the disclosure. Received string recorded: `"…1 more is further than 5 km away."` |
| Closing clean arm | none | 38/38 green, working tree clean |

Both restores were verified by content hash against a pre-arm baseline, never by `git diff --stat`,
which is empty both when a file is restored and when it was never written.

The E2E matchers were falsified at string level rather than by building a deliberately broken image:

```
  "0.2 miles away"   -> ["0.2 miles"]      the post-correction pill
  "0.3 km away"      -> []                  a dropped unit reds the length assertion
  heading /3\.1 miles/  vs  "5 km"    -> false
  heading /3\.1 miles/  vs  "5 miles" -> false   the kilometre figure with the unit swapped
```

The last line is the one worth keeping: the heading is asserted as the **literal** `3.1 miles`, not
as `\d+ miles`, because a loose digit class would accept `5 miles` — the kilometre number wearing a
miles label, which is the single wrong answer that looks most right. Stated honestly, the E2E
distance regex catches a dropped **unit** and the jest arm catches a dropped **conversion**; neither
covers the other, and that division is why both exist.

## The delivered runtime was proven by content, not by a status code

The frontend image was rebuilt and force-recreated, then read back out of what the browser
downloads — 17 chunks concatenated from the live origin:

```
  minified conversion factor  .621371     1 occurrence   (the minifier dropped the leading zero)
  " miles"                                2 occurrences
  CONTROL: any kilometre label            0 occurrences
```

`check-runtime-freshness.sh` **rc=0**, frontend image tagged `09:49:44Z` against the correction
commit at `09:48:22Z`, 4/4 services FRESH by `.Metadata.LastTagTime`. The E2E arms then ran against
that rebuilt runtime, not the pre-correction one.

## Web performance, measured post-grant rather than inherited

`33-03` measured this route with a server-rendered row and nothing else. This plan adds an island
that refetches and re-renders that same row after a grant, so the budget was re-measured in the
state that carries the risk. Throttled 375px, 4x CPU:

| | initial | post-grant |
|---|---|---|
| LCP | 748 ms | 748 ms (budget 8000) |
| CLS | 0.1793 | 0.1793 — identical to `33-03`'s recorded pre-existing baseline |
| Max **vertical** shift | — | **0.00 px** |
| Max horizontal shift | — | 120.00 px — *this is the reorder* |
| "How it works" anchor moved | — | **0.00 px** |
| Client JS | **959,032 bytes** | ceiling 973,833 — 14,801 under |

The island cost **+5,635 bytes** over `33-03`'s baseline; the miles module added **+44** on top.
Routing its one GET through the axios `publicApiClient` instead of `fetch` would put the route at
1,005,834 and red the ceiling by 31,999 bytes — so the ceiling has a regression it demonstrably
catches rather than being a number nobody can defend.

**The post-grant budget had to be replaced, not raised.** The obvious form — sum the layout-shift
entries CLS discards and bound the total — *forbids the feature*: that total is 0.0687 and consists
of exactly one entry, a card moving 120 px horizontally with its y and its height unchanged to the
fractional pixel. That is the reorder the visitor asked for a moment earlier. A budget that reds on
it says "do not ship distance ordering". The criterion is therefore vertical displacement, which is
falsifiable in the direction that matters: putting the distance in the card's flow changes card
height, and un-reserving the status line moves everything below the heading.

## What did NOT close

- **CUST-02 is not closed and its checkbox is still `- [ ]`.** Its #453 limb is carved out by D-2 as
  a decision ticket, and #458's dispatch half and #452 are out of scope per D-3. Stated plainly:
  D-2's recorded rationale explains why nothing was built, but **no adjudicator is named** for
  `MANUAL_REVIEW` — which is exactly what the requirement's second limb asks for. There is no
  cross-tenant operator identity to fall back on, so a vendor stuck in `MANUAL_REVIEW` still reaches
  nobody. The gap survives the phase open, deliberately visible in both `REQUIREMENTS.md` and here.
- **CUST-04 is unknown, not clean.** Out of scope per D-3 and never measured by any plan in this
  phase — nobody looked at the Keycloak theme on either realm or at the staff screen's bulk-revoke.
  An unmeasured requirement reported as "fine" and one reported as "not started" look identical from
  the outside; this is the latter and is recorded as such.
- **CUST-03** is closed by `33-04` on the **recorded-decision limb only** (ADR-0005);
  `identityProviders` remains unpopulated.

### Found at the gate, filed rather than built

| Finding | Issue | State |
|---|---|---|
| A customer postcode with no string match returns 0 shops (e.g. `SE22`) | **#619** | Filed. The FTS path itself measured healthy: `SE15` → 2, `SE15 5BS` → 1, `jollof` → 2, all correct. Search is not coordinate-aware, and `33-06` deliberately refuses `q` combined with `lat`/`lon` rather than silently ignoring one — so "search near me" is a new capability needing its own decision, not a parameter |
| Food-term search is string matching only, no semantic understanding | **#207** | Unchanged — the open pgvector track |

Neither was built. Both were verified against the live stack by the orchestrator before filing.

## Two product limitations, written down before they can be found as bugs

1. **Coordinates are postcode-centroid accurate (~100 m), not door-level.** Two shops sharing a
   postcode are zero metres apart as far as the ranking is concerned. This is why the E2E ordering
   assertion is pinned to the ~2 km Brixton/Peckham pair and never to the ~600 m Peckham pair, which
   sits inside centroid noise for a coarse test coordinate.
2. **Coverage is Great Britain only.** Code-Point Open excludes Northern Ireland. An NI vendor will
   never geocode, **keeps their storefront and their published status**, and is absent from every
   distance-ranked result — but is DISCLOSED by this plan's exclusion notice with a route to the
   full list, rather than vanishing from the platform's primary discovery row. The same applies to
   any postcode newer than the committed snapshot.

## Replaced criteria, each named with its measurement

A vacuous pass reported as satisfied is the defect this phase exists to stop, so every substitution
is recorded rather than made silently.

| Criterion | Why replaced | Measurement |
|---|---|---|
| B2: *"the string 'near you' never appears in the DOM without a coordinate"* | **Unsatisfiable.** `/` renders that phrase at four sites and three are not the heading — the primary CTA, the Browse step body, and the scroller's `aria-label`, which is another spec's selector | Replaced with `getByRole('heading', {name: /near you/i}).toHaveCount(0)`, plus a **control that proves it was scoped and not narrowed**: the CTA and the Browse copy must still be present and must not trip it |
| B4a: `grep -A2 CUST-02 \| grep -qiE 'adjudicat'` | **Already passing before any work** — `REQUIREMENTS.md:149` contained "adjudicates" | Replaced with three conjunctive assertions, each measured: the checkbox limb matched **1** (a regression guard, incapable of failing today), the literal-sentence limb **0** and the GB-only limb **0** (both live). Control: `CUST-01` matched 1, so the pattern machinery works |
| The post-grant shift bound | The strict form **forbids the feature** (see above) | 0.0687 over one entry, 120 px horizontal, 0 px vertical |
| The bundle *multiplier* | A multiplier of a moving baseline ratchets | Replaced with an absolute 973,833-byte ceiling, 456,196 bytes tighter than the 1.5x factor it replaces |

### Two verify limbs are guards, not coverage — and are labelled so

Carried forward from the plan and re-confirmed: (i) the `perf-budgets` import limb was **already
satisfied when this plan ran**, because `33-03` created that import in wave 2, so it guards against
removal and cannot fail here; (ii) the post-grant limb greps for `post.grant|afterGrant|granted`,
which a comment containing the word "granted" would satisfy — it cannot distinguish a real
measurement from a mention. The substantive proof of the post-grant CWV case is the human gate plus
the numbers recorded above. The limb doing real work is the bundle-ceiling one, which greps the
**spec** rather than the budget module and was tested both directions.

## Deviations from Plan

### 1. `fetch`, not the `publicApiClient` the plan's `<interfaces>` pointed at

Measured on the rebuilt stack with the landing route's own bundle meter:

```
  island using publicApiClient (axios)   1,005,834 bytes   +52,481 over 33-03
  island using fetch                       958,988 bytes   + 5,635 over 33-03
```

46,846 bytes of HTTP client on the LCP-critical route, to issue one GET with five query parameters
and read one JSON body. Nothing axios provides is used on that path: no auth interceptor on the
public client, no upload progress, no cancellation, and `public-fetch-retry` is not wired in.
`app/shop/shop-discovery-client.tsx` keeps axios correctly — it pages, searches and consumes
axios-shaped 429s. Rule 2, against the standing web-performance criterion. Recorded in the source
with both readings and an instruction to re-measure if interceptors are ever needed.

### 2. The exclusion count is computed, not subtracted

The plan said to compute N as `serverShops.length` minus the located count. That conflates two
different reasons a shop is missing — *"we do not know where it is"* and *"it is further than you
asked for"* — and would report a shop 20 km down the road as having no location data. The island
counts the shops that genuinely hold no coordinate and discloses the two reasons separately:

> 1 kitchen has no location data yet, so it is not ranked here, and 1 more is further than
> 3.1 miles away. **See every kitchen**.

Inventing a reason is how the row starts lying again, which is the failure class the whole phase
exists to close. Rule 1/2.

### 3. `shop-card.tsx` was modified, and is not in `files_modified`

The distance had to render somewhere. The pill is **absolutely positioned**, which is a CLS decision
rather than a styling one: rendered in flow it would add a line to every card and push the page down
on every grant. Out of flow, located and unlocated cards are byte-identical in height — which is
what makes the measured 0.00 px vertical shift possible. The miles correction changed the string
inside that element and nothing else about it.

### 4. `frontend/lib/distance.ts` and its test are new files outside `files_modified`

Created by the miles correction. The conversion lives in one module, imported by the card that
prints a distance and the row that quotes the radius, so a break arm on the constant is decisive.
Two copies of a magic number are two things to get wrong and only one of them fails a test.

### 5. Two control arms were themselves defective, and were caught by running them

Both were written, both looked fine, and both were repaired before they could report a false
finding:

- **`p:visible` measured 1 on mobile and 0 on DESKTOP.** `hero-scene.tsx` sets `[data-hero-step]` to
  `autoAlpha: 0` inside its desktop `gsap.matchMedia` branch until the step scrolls into view, so
  the Browse paragraph is genuinely not visible at load. The control would have reported deliberately
  out-of-scope copy as **deleted** on every desktop run. Replaced with an assertion against
  `page.content()`, because the claim is about the document, not about what is painted right now.
- **`getByRole("link", {name: /Peckham Jollof/i})` found nothing** while the served HTML contained
  the shop's name five times. The card's link wraps an `<article>`, and Chromium's
  accessible-name-from-content walk does not pull that subtree into the link's name. That absence
  would have read as *"the excluded shop has vanished from the platform"* — the exact defect the arm
  exists to detect. Replaced with the card's heading.

Both are instances of the recorded rule: a negative finding is a hypothesis about your tooling until
a positive control says otherwise.

### 6. Files touched outside `files_modified`

| File | Why |
|---|---|
| `frontend/components/marketing/shop-card.tsx` | Deviation 3 — the distance had to render |
| `frontend/lib/distance.ts`, `frontend/lib/__tests__/distance.test.ts` | Deviation 4 — the miles conversion |
| `docs/metrics.json` | Generated artefact no plan owns; regenerated **by script** |

## Suites and gates

- **Jest:** 97 suites / **880 tests**, 0 failures (checkpoint state was 96 / 868; `distance.test.ts`
  adds 1 suite and 12 tests)
- **Playwright:** `near-you-row` + `landing-webperf`, **20/20 passed** against the rebuilt runtime,
  both projects (mobile and desktop)
- **`npm run build`** rc=0 — the TypeScript gate, since jest does not type-check
- `check-runtime-freshness` **rc=0** (4/4 FRESH) · `docs-freshness` **rc=0** (2591 → **2628**) ·
  `check-doc-metrics` **rc=0** (37 prose claims across 3 docs) · `check-claims` **rc=0** (43 claims
  across 5 docs)
- **The phase's designed doc-gate red is now closed.** `33-06` recorded `check-doc-metrics` rc=1 and
  `check-claims` rc=1 as *"pre-existing, owned by 33-07 Task 4"*. Both are green. The counts were
  regenerated with `scripts/docs-freshness.sh --write` and the prose was then hand-updated, because
  the script does not touch prose — which is the step that has silently rotted before (README sat at
  921 while the tree was at 1895, green on every commit).

## Notes for the next plan

- **`33-07-SUMMARY.md` contains the literal sentence `no adjudicator is named`**, which is the
  phase verifier's remit per the plan's `<success_criteria>` — it could not live in Task 4's
  automated verify, because this file does not exist until the plan completes and `grep -cF` on a
  missing file yields empty, making `test "" -ge 1` exit **2** rather than 1.
- **Miles are a rendering decision only.** If a future change needs the radius in a different unit,
  change what is SENT and let the copy derive from it. Do not relabel: sending `5` to a parameter
  named `radiusKm` while calling it miles would widen the search by 61% and the copy would still
  read 3.1 miles. `near-you-row.test.tsx` asserts the sent value for that reason.
- **The CLS debt on `/` is real and unpaid.** 0.1793 against a 0.1 budget, pre-existing, caused by
  `HeroSearch` hydration and proven so by a control arm running the pre-change build simultaneously.
  It is annotated, not budgeted away.
- **Search is the next honest gap** — #619 (postcode proximity) and #207 (semantic). Neither is a
  small change and neither belongs to CUST-01.

## Threat Flags

None. Every surface this plan introduces is in its own threat register: the coordinate is held in
React state with no write to any browser storage sink (asserted over all four in `-E` form), the
prompt is gesture-gated, one request per grant, and the island imports types from
`types/storefront.ts` rather than from the server module that resolves the internal core host.

## Self-Check: PASSED

- All five created files exist on disk: `near-you-row.tsx`, `near-you-row.test.tsx`,
  `near-you-row.spec.ts`, `lib/distance.ts`, `lib/__tests__/distance.test.ts`.
- All four task commits resolve in `git log`: `b41a9173`, `d8a511e1`, `159b135f`, `7650f988`.
- The literal `no adjudicator is named` appears in **both** `.planning/REQUIREMENTS.md` and this
  file; CUST-02's line still begins `- [ ]`.
