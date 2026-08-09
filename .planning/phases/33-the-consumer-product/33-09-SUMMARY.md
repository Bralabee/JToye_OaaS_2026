---
phase: 33-the-consumer-product
plan: 09
subsystem: storefront-search
tags: [postcode, proximity, search, cors, ssr, honesty, miles, owner-gate]
requires:
  - "33-08 — the X-Search-Interpretation grammar and the postcode search tier"
  - "frontend/lib/distance.ts (33-07) — formatMiles, the single km->miles conversion"
  - "near-you-row.tsx (33-07) — the three-state honesty heading pattern"
provides:
  - "frontend/lib/search-interpretation.ts — the single parser, and the single place a proximity heading can be produced"
  - "StorefrontLoad.headers — the SSR seed can read a response header"
  - "interpretation-first search ordering (D-A, reversed at the owner gate)"
affects:
  - "GET /api/v1/public/shops?q= — a resolvable postcode is now answered as a place BEFORE any text match"
  - "/shop and /shop?q= — the summary line, the exclusion disclosure, the distance pill"
  - "docs/api/openapi-snapshot.json — the endpoint description stated the old ordering"
tech-stack:
  added: []
  patterns:
    - "Server-asserted disclosure parsed once, in one module, with every unparseable input degrading to the no-claim state"
    - "An SSR seed that carries a response header, so the first paint is already honest"
    - "A far-away namesake fixture: a text-matchable row parked outside every radius, so an ordering change is observable"
key-files:
  created:
    - frontend/lib/search-interpretation.ts
    - frontend/lib/__tests__/search-interpretation.test.ts
    - frontend/app/shop/__tests__/shop-discovery-client.test.tsx
  modified:
    - frontend/lib/storefront-server.ts
    - frontend/types/storefront.ts
    - frontend/app/shop/page.tsx
    - frontend/app/shop/shop-discovery-client.tsx
    - frontend/e2e/storefront-flows.spec.ts
    - frontend/__tests__/shop/rate-limit.test.tsx
    - frontend/__tests__/shop/server-seeded-islands.test.tsx
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontService.java
    - core-java/src/main/java/uk/jtoye/core/storefront/PublicStorefrontController.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontPostcodeSearchIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - docs/CHANGELOG.md
    - CLAUDE.md
    - AGENTS.md
    - README.md
decisions:
  - "D-A REVERSED at the owner gate: interpretation-first. A resolvable postcode is a locality question, answered before any text match"
  - "D-D upheld: the exclusion disclosure is generic, not counted — recorded as strictly weaker than 33-07's counted form"
  - "The wording stands as written: '3.1 miles', never a tidier '3 miles'"
  - "searchSummary returns a discriminated union, not a bare string, so the text branch keeps the emphasis the page already had"
metrics:
  duration: "~3h 40m"
  completed: 2026-08-09
  commits: 5
---

# Phase 33 Plan 09: Postcode-Proximity Search — the customer-visible half Summary

A customer who types a postcode into shop search now sees nearby kitchens, each with its distance
in miles, above a line saying which question the server answered — and the storefront can only
repeat a proximity claim the server made, never invent one.

---

## The checkpoint verdict, verbatim

> 1. **Walkthrough: "Approved"**
> 2. **D-A: "Interpretation-first"** — a full postcode that matches a shop's own address is a
>    LOCALITY question. SE15 5BS should return every kitchen nearby (distance-ordered,
>    proximity-disclosed), not just the one at that address. Any postcode-shaped q that geocodes is
>    answered as proximity; a postcode-shaped q that does NOT geocode (ZZ99 9ZZ, NI postcodes)
>    still falls through to FTS exactly as today; non-postcode-shaped queries untouched.
> 3. **Wording: "Keep as written"** — the 3.1-miles copy and the generic exclusion line stand.

Approved with one reversal. The flip shipped as its own commit (`3b038825`) before this SUMMARY was
written, with the affected arms re-run against a re-rebuilt runtime.

---

## What shipped

**`frontend/lib/search-interpretation.ts`** — the only parser of `X-Search-Interpretation` and the
only place a proximity heading can be produced. Absence, an empty value, an unknown kind, a missing
or non-finite `radiusKm`, an unknown `precision`, a key outside the server's own `[A-Z0-9]{2,8}`
charset, and any control character all return `{kind:"text"}`. That is the whole safety argument:
the UI can only ever **fail to claim** proximity, never invent one.

**The SSR seed carries the interpretation.** `app/shop/page.tsx` reads the header off the
server-to-server response and passes it down; `getJson` was widened to `{state:"ok"; data; headers?}`,
optional so `loadShopDetail` — which builds its own `ok` from six responses — compiles untouched.
Without the seed, the island's `serverSeeded` ref suppresses the mount fetch and `/shop?q=SE22`
would render the plain heading over proximity-ordered results **permanently** (measured, below).

**The island** renders `3 kitchens within 3.1 miles of SE22`, the generic exclusion line with a
*See every kitchen* route out, and — new to this file's own `ShopCard`, which rendered no distance
at all — an absolutely-positioned distance pill, top-left because the Open/Closed badge owns
top-right. On a 429 or a network failure the interpretation resets to `text`.

**D-A reversed.** `PublicStorefrontService.searchPublishedShops` now offers `q` to the geocoder
first. `locateSearchTerm` applies its length bound and anchored shape test before any lookup, so an
ordinary food search issues **zero** queries there — the flip costs a regex, not a round trip.

---

## Live measurements, taken not inherited

**Before** (33-08's control, against the pre-rebuild runtime): `?q=SE22` → **0 shops, no header**.

**After the flip**, against the rebuilt runtime:

| query | total | header | distances |
|---|---|---|---|
| `SE22` | **3** | `proximity; postcode=SE22; precision=district; radiusKm=5.0` | 1.376 / 2.007 / 2.877 km |
| `SE15 5BS` | **3** | `proximity; postcode=SE155BS; precision=unit; radiusKm=5.0` | **0.000** / 0.661 / 3.092 km |
| `SE15` | **3** | `proximity; postcode=SE15; precision=district; radiusKm=5.0` | 0.312 / 0.850 / 3.397 km |
| `Nigerian` | 2 | `text` | null |
| `jollof` | 2 | `text` | null |
| `ZZ99 9ZZ` | 0 | `text` | — |
| `BT1 5GS` | 0 | `text` | — (Code-Point Open is GB only) |

The `0.000 km` first result on `SE15 5BS` is the kitchen **at** that address — now followed by its
neighbours instead of standing alone. In the browser those render `0.0 / 0.4 / 1.9 miles`.

**Two behaviours moved, and neither is glossed.** `?q=SE15 5BS` went from 1-by-text to
3-by-proximity, which is the decision. `?q=SE15` went from **2-by-text to 3-by-proximity**, which is
one of 33-07's live-measured "healthy" behaviours — covered by the owner's rule ("any postcode-shaped
q that geocodes is answered as proximity") but a change to a previously-measured number, so it is
recorded in `docs/CHANGELOG.md` under **Changed** rather than left to be discovered.

---

## Control arms — every one run in the fail direction, both outputs recorded

Committed before every arm; every restore verified by `git hash-object` against a pre-arm baseline;
the clean direction re-run **last**.

### CA-H — the header is readable by browser JavaScript (the arm curl cannot replace)

**BREAK** — `X-Search-Interpretation` removed from `CORS_EXPOSED_HEADERS`, core-java recreated:

```
wire   (curl -H 'Origin: http://localhost:3000'):
  Access-Control-Expose-Headers: Authorization, Content-Type, Retry-After, X-RateLimit-Limit,
                                 X-RateLimit-Remaining, X-RateLimit-Reset
  X-Search-Interpretation: proximity; postcode=SE22; precision=district; radiusKm=5.0
browser (page.evaluate fetch):
  {"status":200,"header":null}
  -> Expected: "proximity; postcode=SE22; precision=district; radiusKm=5.0"
     Received: null                                                    ARM RED
```

**CLEAN (re-run last):** browser `{"status":200,"header":"proximity; postcode=SE22; …"}`, allowlist
carries the seventh name, 1 passed.

This is #412's exact shape. **Worth recording beyond the arm itself:** Playwright's own `request`
fixture read the header correctly in *both* directions, because an `APIRequestContext` is not a
browsing context and CORS does not apply to it. An API-request-based test would have gone green over
a header no browser could see.

### CA-I — the disclosure is in the server-rendered HTML

**BREAK** — the `page.tsx` pass-through reverted, frontend rebuilt:

```
proximity copy occurrences in the raw server HTML: 0
POSITIVE CONTROL, same grep, same document, text that IS there:
  'Discover local kitchens' occurrences: 1
Playwright: Error: the proximity copy is absent from the server-rendered document   ARM RED
```

**The plan's prediction was wrong in the safe-sounding direction, and the truth is worse.** It
expected `page.content()` to lack the copy *"while the hydrated page shows it"* — i.e. a flash.
Measured on the broken build after 6 s of hydration:

```
{"proximityCopyAfterHydration":0,"plainCopyAfterHydration":1,"cards":3}
```

Three proximity-ordered cards under the plain *"kitchens for"* wording, and it never corrects
itself, because `serverSeeded` suppresses the mount fetch. The defect the SSR seed prevents is a
**standing untruth**, not a transient one.

**CLEAN (re-run last):** 1 occurrence in the raw HTML.

### CA-D — the UI never claims proximity unbidden

**BREAK** — `searchSummary` emitting the proximity form unconditionally: 5 of 13 island arms red,
including CA-D's negative arm, **while its sibling CONTROL stayed green** — which is the
discrimination that makes the pair evidence:

```
✕ CA-D: renders today's copy and NO proximity claim when the server said `text`
    expect(received).toBeNull()
    Received: <p aria-live="polite" …>2 kitchens within 3.1 miles of SE22<span …>Kitchens we
              cannot place, and any further away, are not shown. <a href="/shop">See every
              kitchen</a>.</span></p>
✓ CA-D CONTROL: the SAME query finds exactly one node when the server disclosed proximity
```

**CLEAN (re-run last):** 60/60. Restore verified — `16c18f4251f9fbd9a78425ab13812353732ba4b4`.

### The catch-branch reset — a non-answer carries no claim

**BREAK** — `setInterpretation(TEXT_INTERPRETATION)` removed from the catch:

```
✓ 429 arm: the busy state replaces the claim that was on screen a moment before
✕ failure arm: a network error falls back to the TEXT copy, never the proximity copy
    Unable to find an element with the text: /No kitchens match/i
```

The 429 arm stays green under the break **because the busy state hides the summary either way** —
which is precisely why the network-failure arm is the decisive one, and why both exist. Restore
verified — `41e60702f00a7d6606275dc1f352474f24a4089d`.

### D-A — the flip itself

**BREAK** — the interpretation tier deferred behind the text tiers (the shipped-33-08 order):

| suite | result |
|---|---|
| `PostcodeSearchTier` unit | **3 of 5 RED**, incl. `D-A FLIP: … expected: <PROXIMITY> but was: <TEXT>` |
| `PublicStorefrontPostcodeSearchIntegrationTest` | **exactly 1 of 15 RED** — the D-A FLIP arm |

The precision matters: 14 of 15 integration arms stayed green, so the flip provably did not disturb
the fall-through contract. `CA-A` and `unresolvablePostcodeStaysText` stayed green in **both**
directions — correct, since ordering cannot affect a term the geocoder declines. Restore verified —
`8981e2bf5b1dbc4bfb568919f61a72dd48cbba13`; no `BREAK ARM` / `textWouldAnswer` token survives in
source (the only hits were gitignored `build-local` reports from the break run).

### CA-C(ui), CA-E, CA-J(frontend)

- **CA-C(ui):** `Nigerian` → `getByText(/within .* miles of/i)` count **0** while the shop-name
  assertion still passes. Positive control is the SE22 arm, which finds the same wording once.
- **CA-E:** the literal `[A-Z]{1,2}` and the shape-specific `[A-Z]{1,2}\d` both match **0** times in
  `search-interpretation.ts`, `app/shop/page.tsx` and `shop-discovery-client.tsx`; the same patterns
  match **1** in `app/shop/[slug]/checkout/page.tsx`. A directory sweep over `frontend/app/shop/`
  returns that file and nothing else. (`grep -F`, because `grep` here is ugrep and braces are
  metacharacters.)
- **CA-J(frontend):** `docs-freshness.sh` **rc=1** before `--write` both times it ran, printing the
  real deltas — that rc=1 is what proves the counter can see the new blocks. rc=0 after.

### Absence claims, each with a positive control

| Absence claim | Result | Positive control |
|---|---|---|
| no UK-postcode regex in the three named files | 0 | the same pattern finds **1** in `checkout/page.tsx` |
| `MILES_PER_KM` absent from the parser test | 0 | the same pattern finds **2** in `lib/distance.ts`, **5** in `distance.test.ts` |
| no customer-visible `km` string in the island | 0 | every `km` occurrence enumerated: 3, all the identifier `distanceKm` |
| no dependency manifest in the changed set | 0 | the same pattern matches **3** when fed those three paths |
| `BREAK ARM` token gone after restore | 0 in source | the same scan finds **1** pre-existing line in `distance.ts`, so it can match |

---

## Verification

| Suite / gate | Result |
|---|---|
| `cd frontend && npx jest` | **99 suites / 944 tests / 0 failures** (884 before) |
| `cd frontend && npm run build` (the TypeScript gate) | rc=0 |
| `cd frontend && npx playwright test storefront-flows` | **44 passed**, both projects (42 before the D-A arm) |
| `./gradlew :core-java:test --rerun-tasks` | **145 classes / 1065 tests / 0 failures** |
| `./gradlew :core-java:integrationTest --rerun-tasks` | **120 classes / 542 tests / 0 failures** (33-08: 540; delta = the two D-A arms) |
| `check-runtime-freshness.sh` | rc=0 — 4/4 FRESH |
| `check-openapi-snapshot-fresh.sh` | rc=0 — **flipped from 33-08's recorded rc=1** (Open Item 1 closed) |
| `docs-freshness.sh` / `check-doc-metrics.sh` | rc=0 / rc=0 (37 claims, 3 docs) |
| `check-claims.sh`, `check-gate-enforcement.sh`, `check-e2e-typecheck.sh` | rc=0 |
| `check-branch-behind-base.sh` | rc=0 — 0 behind `origin/main` |
| `check-no-create-extension.sh` | rc=0 |
| `check-alert-metrics.sh` | rc=1 pre-seed (the recorded reaction to a core-java rebuild), rc=0 after `seed-order-metric.sh` |

Gradle printed `BUILD SUCCESSFUL` without a test summary; execution was verified by reading the
result XML. **The first read was from `core-java/build/test-results/`, which is a 2025-12-27 relic
reporting `4 classes / 6 tests / 3 failures`** — the recorded stale-artifact trap. The live directory
is `build-local` (`layout.buildDirectory.set(file("build-local"))`), mtime matching the run.

Metrics, regenerated by script and never by arithmetic: `jest_blocks` 884→944, `jest_files` 97→99,
`playwright_blocks` 95→**101**, `java_test_methods` 1578→1580, total **2686 → 2754**.

---

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 3 - Blocking] Two pre-existing callers of the island did not compile against the widened prop**

- **Found during:** Task 1, by the **full** Jest suite — not by this plan's own `<verify>` limb,
  which named only the two new files.
- **Issue:** `__tests__/shop/rate-limit.test.tsx` and `__tests__/shop/server-seeded-islands.test.tsx`
  render `<ShopDiscoveryClient>` directly. With `initialInterpretation` required and absent,
  `searchSummary(undefined, …)` threw and 4 arms red.
- **Fix:** both call sites pass `initialInterpretation={{ kind: "text" }}`, **and** the island seeds
  with `initialInterpretation ?? TEXT_INTERPRETATION`. The `??` is not slack in the contract — the
  prop stays required and `page.tsx` always supplies it. It converts a hard crash of the storefront
  index into the same fail-safe direction the whole module takes: it can only fail to repeat a
  claim, never manufacture one.
- **Commit:** `dffc13f2`

**2. [Rule 1 - Bug] The parser's control-character guard was written with literal control bytes**

- **Found during:** Task 1, immediately after the edit.
- **Issue:** the regex landed in source as raw `NUL`–`0x1F`,`0x7F` bytes. Functionally correct, but
  it made the file **binary to `grep`**, so `grep -n CONTROL_CHARACTERS` silently returned nothing —
  an absence that was an artefact of the file, not of its content.
- **Fix:** rewritten as `/[\u0000-\u001F\u007F]/`, with a scan asserting zero stray control
  characters remain. The guard is load-bearing: without it `postcode=SE22\r` trims to a valid key
  and would parse as proximity.
- **Commit:** `dffc13f2`

### Criteria corrected rather than silently satisfied

**`MILES_PER_KM == 0` was unsatisfiable as written.** The criterion demands the identifier appear
zero times in the parser test — but the test's own docblock stated the rule, and naming a forbidden
token in the rule that forbids it is a recorded vacuity shape here. Reworded to describe the
constant instead of naming it, so the criterion passes at a genuine **0**; the **stronger** form (no
import, no expression use) was also measured, with `distance.test.ts` as the positive control
showing 3 matching lines.

**`searchSummary` returns a discriminated union, not the bare string the plan specified.** A bare
string would have silently deleted the oxblood-emphasised quoted term the text branch already
rendered — a regression by omission that every test would have passed over. The union keeps that
emphasis and still exposes the whole line as one string.

### Not a deviation, but worth stating

**`docker compose up -d --build` rebuilt three images and left their containers on the old IDs.**
`check-runtime-freshness.sh` caught it as `container-not-recreated` on three separate occasions;
`--force-recreate` was required each time. Rebuilding one service also re-tags others, so the gate
has to be re-run after **every** rebuild, not once at the end.

**`check-doc-metrics.sh` reported 33 claims instead of 37 and failed.** My first prose edit had
reworded two README lines to `1580 Java \`@Test\` methods` and `101 Playwright \`test()\` blocks`,
which no longer match the patterns the gate anchors on. It reported `rule matched NOTHING — the
claim was removed or reworded` rather than passing over a claim it could no longer see. That is the
behaviour a count-in-prose gate must have, and it is the half of the loop that has silently rotted
before.

---

## Known Stubs

None. Every rendered branch is wired to a real server response.

---

## Threat model outcomes

| Threat | Disposition | Evidence |
|---|---|---|
| T-33-09-01 the UI claiming an interpretation the server did not make | mitigated | the heading is derivable only from the parsed header; 24 malformed inputs all degrade to `text`; CA-D permanent in both directions and observed red under break; CA-E shows no postcode regex with a positive control |
| T-33-09-02 scraping the public directory | accepted | no new endpoint. Proved in-browser: `x-ratelimit-limit/remaining/reset` all non-null, so 33-08's CORS edit added a name rather than displacing the six |
| T-33-09-03 the header readable cross-origin | accepted | it carries the caller's own query term normalised, plus precision and radius. No coordinate |
| T-33-09-04 internal core host in the client bundle | mitigated | `storefront-server.ts` is imported only by the server page; the island imports **types** and uses `publicApiClient` |
| T-33-09-05 reflected content in the summary | mitigated | React text child, never `dangerouslySetInnerHTML`; on the proximity branch the postcode comes from the server's `[A-Z0-9]{2,8}` key — asserted by an arm feeding `"se 22 <script>"` and finding `SE22` with no script tag |
| T-33-09-SC dependencies | accepted | zero manifests in the changed set, with a positive control |

No new threat surface. The D-A flip changes which query runs first, not what any query may reach:
the same `findPublishedNear`, the same RLS-free reference table, the same rate limiter.

**One note for the security record:** the geocoder now runs on **every** `?q=`, not only on
zero-result searches. Its DoS control (`MAX_SEARCH_TERM_LENGTH`, then an anchored bounded regex) sits
*before* both lookups, so the exposure is unchanged — re-proved live by the 400-character `q` arm,
which is still answered by the text page with header `text`.

---

## Open items

1. **`?q=SE15` moved from 2-by-text to 3-by-proximity.** Intended under the owner's rule, recorded
   in the CHANGELOG, and worth a glance at the next walkthrough since it was a 33-07 baseline number.
2. **The plan's environment note said `?q=SE15 5BS → 1 shop (unit tier)`.** That was wrong even
   before the flip: 33-08 shipped it as a **text** answer. Measured, recorded, and now moot.
3. **Squash-merge with an explicit `(#NNN)` subject.** `check-changelog-contract.sh` keys on a PR
   number extracted from the commit subject and a rebase-merge strips it, which VOIDs the gate on
   `main` and first surfaces on somebody else's unrelated PR.

---

## Self-Check: PASSED

Files asserted present on disk:

- `frontend/lib/search-interpretation.ts` — FOUND
- `frontend/lib/__tests__/search-interpretation.test.ts` — FOUND
- `frontend/app/shop/__tests__/shop-discovery-client.test.tsx` — FOUND (265 lines, min_lines 120 satisfied)
- `frontend/e2e/storefront-flows.spec.ts` — FOUND, contains `3.1 miles`

Commits asserted present in `git log`:

| Commit | Message |
|---|---|
| `d9352a17` | `test(33-09)`: RED — the honesty arms fail against a text-only stub |
| `dffc13f2` | `feat(33-09)`: GREEN — the storefront repeats the server's reading of q |
| `806d6a7b` | `test(33-09)`: five live arms against the rebuilt runtime, and the counted docs |
| `3b038825` | `feat(33-09)`: D-A flipped to interpretation-first, per the owner gate |
| `fa735e87` | `docs(33-09)`: recount and restate the contract after the D-A flip |

TDD gate sequence: `test(33-09)` RED → `feat(33-09)` GREEN, the RED commit recording **22 real
assertion failures** (38 passing) against a deliberate stub rather than a compile error.

Clean state asserted **last** as well as first: all four break-arm targets are at their pre-arm
content hashes, no `BREAK ARM` marker survives in source, the working tree is clean against HEAD, no
commit in this plan deleted a tracked file, and no untracked file was left behind.

`STATE.md` and `ROADMAP.md` were **not** modified — `git log d9352a17~1..HEAD -- .planning/STATE.md
.planning/ROADMAP.md` lists no commits. The branch is **0 behind** `origin/main`. No pushes, no
branch changes.
