---
phase: 31-consumer-safety-and-legal-floor
plan: 16
subsystem: frontend-legal
tags: [pecr, consent, cookies, cls, falsifiability, client-only]
requires: [31-01, 31-08]
provides:
  - "frontend/lib/consent.ts — client-only consent store and script gate"
  - "frontend/components/public/cookie-notice.tsx — the essential-cookies notice"
  - "frontend/components/public/consent-banner.tsx — dormant consent banner"
  - "storage key `jtoye-cookie-notice-ack` (must be disclosed by 31-11's /legal/cookies)"
affects:
  - "frontend/app/layout.tsx — root mount, sole editor of this file in wave 3"
tech-stack:
  added: []
  patterns:
    - "client-only consent tier (no table, no migration) — settled, D-05"
    - "@jest-environment node for a REAL SSR guard (delete global.window is a no-op here)"
    - "layout-cost guard replacing a vacuous CLS break arm"
key-files:
  created:
    - frontend/lib/consent.ts
    - frontend/lib/__tests__/consent.test.ts
    - frontend/lib/__tests__/consent.ssr.test.ts
    - frontend/components/public/cookie-notice.tsx
    - frontend/components/public/consent-banner.tsx
    - frontend/components/public/__tests__/cookie-notice.test.tsx
    - frontend/e2e/cookie-notice-layout.spec.ts
  modified:
    - frontend/app/layout.tsx
    - docs/metrics.json
decisions:
  - "Version comparison is EXACT MATCH, not semver — the 'was this material?' judgement has no owner and fails silently"
  - "Notice takes z-40, BELOW FloatingCartBar/mobile-tab-bar at z-50 — ranked by z-index, not a pixel offset that drifts"
  - "The planned position:static CLS break arm is VACUOUS; replaced with a strictly stronger layout-cost guard"
metrics:
  tasks: 2
  tests_added: 41
  duration: single session
  completed: 2026-08-16
---

# Phase 31 Plan 16: Essential-Cookies Notice + Consent Gate Summary

A client-only consent store and script gate — **no table, no migration** — plus the
essential-cookies notice and a dormant consent banner, with the gate proven to block a script
before a choice and permit it after against a fixture category, because a gate over zero
categories cannot fail as shipped.

## What shipped

| Artifact | What it does |
|---|---|
| `frontend/lib/consent.ts` | Versioned dismissal key (defined exactly once), fail-closed `isAllowed`, `loadWhenAllowed` gate, SSR guard, private-mode try/catch, same-tab + cross-tab notification |
| `frontend/components/public/cookie-notice.tsx` | Labelled `<section>`, verbatim copy, dismissible, `z-40` fixed bottom, cream focus ring, 44px target |
| `frontend/components/public/consent-banner.tsx` | Dormant; renders only when a non-essential category is registered |
| `frontend/e2e/cookie-notice-layout.spec.ts` | Permanent falsifiable layout-cost guard (replaces the vacuous CLS arm) |
| `frontend/app/layout.tsx` | Root mount (+2 lines + comment) so `/shop/[slug]`, which has no `PublicShell`, is covered |

**The shipped configuration registers ZERO non-essential categories, so no banner appears
today.** The fixture category exists only in tests.

## FOR 31-11 — the storage key that must be disclosed

Under PECR this key is storage on terminal equipment exactly as a cookie is, so an
undisclosed key makes the cookie policy wrong.

| Field | Value |
|---|---|
| **Key name** | `jtoye-cookie-notice-ack` |
| **Mechanism** | `localStorage` (not a cookie) |
| **Value stored** | The policy version string, currently `2026-08-16` |
| **Lifetime** | **Persistent — no expiry.** `localStorage` survives browser restart and is cleared only by the user clearing site data, or implicitly when the policy version changes and the old value is overwritten |
| **Purpose** | Records that the visitor dismissed the notice, so it is shown once |
| **Category** | Strictly necessary (a preference the user expressed) |

A **second** key exists and should also be disclosed for completeness:
`jtoye-cookie-consent-choices` — `localStorage`, persistent, a JSON map of category id →
`accepted`/`rejected`. It is **never written today** (zero non-essential categories) but the
code path exists.

## Decisions with no analog in the tree

**Version comparison — EXACT MATCH.** Measured: no storage key in this repo is versioned, so
there was nothing to copy. Any stored value other than the current one re-shows the notice.
The semver alternative ("patch changes do not re-prompt") was rejected because it requires a
per-edit judgement about whether a disclosure change was "material", that judgement has no
owner, its failure mode is silent (a changed disclosure that never re-shows), and no test
could catch it because the rule would be a matter of opinion. An unnecessary re-prompt is a
far cheaper error than an undisclosed change.

**Stacking — `z-40`, below the bars.** `FloatingCartBar` and `mobile-tab-bar` both sit at
`z-50`, bottom-anchored, and nothing in the tree resolved the collision. The notice is ranked
**below** both: the basket bar is a transactional control on the path to checkout and the tab
bar is primary navigation, so an informational notice must never occlude either. A z-index
was chosen over a pixel offset because an offset must be re-tuned whenever either bar changes
height and **fails silently as a covered CTA**. Uses `FloatingCartBar`'s
`pb-[max(0.75rem,env(safe-area-inset-bottom))]` form — a plain `pb-[env(...)]` collapses to 0
on non-notch devices.

## Measurements

**Zero analytics scripts — with the positive control, not a bare empty grep.**

```
TREATMENT  rg -uu -i -e 'googletagmanager|google-analytics|gtag\(|gtm\.js|fbevents|
           facebook\.net|hotjar|segment\.io|mixpanel|plausible\.io|posthog|clarity\.ms|
           doubleclick|analytics\.js' frontend/app frontend/components frontend/lib
           -> rc=1  (no hits)

CONTROL    same command shape, token known present:
           rg -uu -i -e 'framer-motion' frontend/app frontend/components frontend/lib
           -> 36 hits
```

The control proves the search reaches those directories, so `rc=1` is evidence about the
tree rather than about my pattern. The only `<script` tags in `app/` are three
`type="application/ld+json"` structured-data blocks — inert data, not executable script, and
not a cookie category. (A first, looser pattern matched the prose word "plausible" twice and
was tightened to `plausible.io`.)

**Core Web Vitals / bundle**, measured on **my own build** served on `:3200`. The running
`jtoye-frontend` container is 4 days old and does **not** contain the notice, so measuring
`:3000` would have measured a stale artifact.

| Metric | Baseline | Measured | Verdict |
|---|---|---|---|
| `/` CLS (throttled mobile, 375px, 4× CPU) | `LANDING_CLS_KNOWN_BASELINE = 0.1793` ± 0.02 | **0.1793** | delta **0.0000** |
| `/` client JS | `953,353` bytes | **940,430** bytes (918.4 KiB) | **12,923 bytes BELOW** baseline |

## Break arms — both directions, clean → arms → clean

Committed before every arm; every restore verified by `git hash-object` against the committed
blob (`consent.ts` = `2c700405…`, `cookie-notice.tsx` = `5b08edff…`), never `git diff --stat`.

### Task 1 — `lib/consent.ts` (baseline clean arm: 24/24 pass)

| Arm | Break | Real output |
|---|---|---|
| **(a) F1 — the gate is real** | `isAllowed` returns `true` unconditionally | **FIRES.** `7 failed, 17 passed`. Block arm: `expect(isAllowed(FIXTURE.id)).toBe(false)` → `Expected: false / Received: true` |
| **(b) fail-closed** | unknown category returns `true` | **FIRES.** `3 failed, 21 passed` — the fail-closed test, the unregistered-orphan test, and the SSR read test |
| **(c) zero-category is not vacuous** | added a non-essential entry to `SHIPPED_CATEGORIES` | **FIRES.** `Expected Array [] / Received Array [ Object { "essential": false, "id": "break-arm-analytics" … } ]` |
| **(d) same-tab notification** | removed the `CustomEvent` dispatch | **FIRES.** `2 failed, 22 passed` — the same-tab test and the choice-recorded test. The **cross-tab test still passed**, confirming the two channels are independently asserted |

**Closing clean arm: `2 passed, 24 tests passed`.**

### Task 2 — the notice (baseline clean arm: 17/17 pass)

| Arm | Break | Real output |
|---|---|---|
| **(b) copy is exact** | paraphrased the body sentence | **FIRES.** `1 failed, 16 passed` — `Unable to find an element with the text: We only use cookies and browser storage that are strictly necessary…`. **The other 16 tests passed on paraphrased legal copy**, which is precisely why fixed strings are used |
| **(c) not a dialog** | added `aria-modal="true"` | **FIRES on three independent instruments** — the not-a-dialog assertion, the axe scan (`2 failed, 15 passed`), and the grep guard (`aria-modal -> 1 *** PRESENT ***`) |
| **(d) dormant banner** | register / unregister a fixture category | **Both directions asserted and passing** — absent with the shipped config, present with the fixture |
| **(a) F2 — zero CLS** | `position: static` | **DID NOT FIRE — see below** |

**Closing clean arm: `17/17 pass`; full suite `109 suites / 1049 tests` green.**

## The F2 arm was VACUOUS — reported, not silently substituted

The plan's criterion was "measure CLS on `/` and break it by making the notice
`position: static`". It was run. **It cannot fail:**

```
position: fixed   ->  CLS = 0.1793
position: static  ->  CLS = 0.1793      (identical to 4 decimal places)
```

The reason is structural, not incidental. The notice mounts at the **end of `<body>`**, and
layout shift scores the movement of content that is *already laid out* — appending an element
below everything moves nothing. On this page it lands ~1200px down, well past a 812px fold.
The page-level number is additionally dominated by the hero island's pre-existing 0.1793, so
the notice's contribution would be invisible in it even if there were one.

Reporting "CLS unchanged, therefore zero layout shift" from that pair would be a criterion
observed only passing.

**Strictly stronger replacement** (`frontend/e2e/cookie-notice-layout.spec.ts`, committed as a
permanent guard): on the **same build**, with the notice suppressed via its own ack key so the
two arms differ only in whether the notice is in the DOM, assert that document height and the
page-space Y of a stable landmark are unchanged — plus a **positive control** proving an 80px
in-flow append is detectable.

| Arm | scrollHeight (shown / hidden) | anchorY | Result |
|---|---|---|---|
| **clean (`fixed`)** | 3211 / 3211 | 1593.23 / 1593.23 | **delta 0 — PASSES** |
| **broken (`static`)** | delta **232px** | — | **FAILS**: `Expected: <= 2 / Received: 232` |

It is stronger on three counts: it isolates the notice's own contribution instead of drowning
it in a page-level score; it **does** fire for `position: static`; and it carries its own
non-vacuity control.

## Deviations from Plan

**1. [Rule 3 — blocking] The SSR test could not work as planned; moved to a node environment.**
- **Found during:** Task 1 GREEN.
- **Issue:** The planned single test file used the standard `delete global.window` idiom. Probed directly: `window` is defined `configurable: false` in this jsdom, so `delete` returns **`false`** and `typeof window` stays `"object"`. The idiom is a **no-op**. The dangerous part is that it *mostly passes* — three of its four assertions are true with `window` present, so it reads green while proving nothing about SSR. Only `shouldShowCookieNotice()` discriminated, and it is the one that failed and exposed the no-op.
- **Fix:** SSR cases moved to `frontend/lib/__tests__/consent.ssr.test.ts` with `/** @jest-environment node */` (the repo's established idiom — 11 existing files), which gives a *real* absence of `window`. It opens with a non-vacuity control asserting `typeof window === "undefined"`, so losing the docblock cannot silently downgrade it to a jsdom duplicate.
- **Scope check:** I searched the repo — **no pre-existing test uses this idiom**, so nothing else is compromised.
- **Commit:** `68eb5777`

**2. [Rule 1 — bug] A comment satisfied the grep that forbids a phrase.**
- **Found during:** Task 2 verify.
- **Issue:** The acceptance criteria require the phrase `cookies only` to be absent from `cookie-notice.tsx`. My own header comment quoted it in order to forbid it, so `grep -ciF` returned 1 and the guard was useless — the known "a doc rule must name the token it forbids" shape, which this phase has hit repeatedly.
- **Fix:** Comment reworded to describe the banned phrasing without containing it, with a note explaining why. Rendered copy was already correct and is asserted on `textContent` by the jest test, which is the stronger check.
- **Commit:** `1553d370`

**3. [Rule 2 — missing critical] Added `frontend/e2e/cookie-notice-layout.spec.ts`, not in `files_modified`.**
- **Rationale:** The planned CLS criterion is unfalsifiable (above). A one-off note in this summary would leave the tree with no guard at all, so the replacement was committed as a permanent spec — the same treatment `__tests__/axe-instrument.test.tsx` already gets.
- **Commit:** `d994dd10`

**4. [Rule 3 — blocking] Worktree was branched from the wrong base.**
- **Found during:** startup, before any work.
- **Issue:** This worktree was created at `bb2ae65d` (main's tip, Phase 28) while all five sibling wave-3 worktrees sat at the intended base `0d1834c2`. The Phase 31 planning directory did not exist, so the plan file could not be read. `bb2ae65d` is a strict **ancestor** of `0d1834c2` (0 commits on main not in phase/31; 76 the other way), and the tree was clean with no commits of my own.
- **Fix:** `git merge --ff-only 0d1834c2` — a clean fast-forward, no divergent work, no reset. Had I proceeded on the wrong base, the merge would have dragged Phase 28 into the phase branch and shipped a runtime missing all of wave 1 and 2.

## Threat model — dispositions

| Threat | Disposition | Evidence |
|---|---|---|
| T-31-16-01 gate cannot fail as shipped | mitigated | Fixture block+permit in one test (arm a fires), plus a **separate** zero-category assertion (arm c fires). Neither stands in for the other |
| T-31-16-02 client-side consent tampering | accepted | Only optional script loading is gated, and there are none. Recorded in `loadWhenAllowed`'s comment: a future security-relevant category reopens this row |
| T-31-16-03 unknown category elevates | mitigated | Fails closed; arm (b) fires |
| T-31-16-04 notice blocks CTA / basket bar | mitigated | `z-40` below both bars, `max()` inset. Additionally A/B'd in a browser: with the notice removed from the DOM the landing CTA behaves identically |
| T-31-16-05 "cookies only" claim | mitigated | Verbatim copy says "cookies and browser storage"; phrase asserted absent on both source and rendered `textContent` |
| T-31-16-06 modal consent wall | mitigated | Labelled `<section>`; `aria-modal`, dialog roles and scroll-lock all asserted absent; arm (c) fires |
| T-31-16-07 layout shift | mitigated | Not by the planned CLS arm (vacuous) but by the stronger layout guard: 0px vs 232px |
| T-31-16-SC package installs | accepted | **No new dependency.** framer-motion already app-wide |

## Merge-gate items for the orchestrator

1. **`/legal/cookies` must exist.** This plan asserts the `href` string only; 31-11 creates the route in this same wave. The end-to-end 200 assertion belongs to 31-17.
2. **31-11's cookie policy must disclose `jtoye-cookie-notice-ack`** (and ideally `jtoye-cookie-consent-choices`) with the lifetimes in the table above.
3. **Re-run `scripts/docs-freshness.sh --write` on the merged tree**, then reconcile the prose counts in `README.md` / `CLAUDE.md` / `AGENTS.md`. My `docs/metrics.json` (total 2993) is correct for this worktree alone — a worktree cannot see its siblings' tests. `check-doc-metrics.sh` will fail until that single post-merge reconciliation. I deliberately did not touch the prose files.
4. **`frontend/app/globals.css` untouched** — verified absent from the change set; 31-15's ownership in this wave is intact.
5. **`frontend/app/legal/page.tsx` untouched** — the index already links `/legal/cookies`.

## Out of scope, logged not fixed

`landing-webperf.spec.ts`'s **post-grant** test (2 of 8) fails when the app is served from a
host `next start` rather than the compose container, showing *"We could not check what is near
you just now — showing every kitchen."* It is **provably not caused by this plan**: an A/B on
the same artifact, suppressing the notice via its ack key, reproduces the failure identically
with the notice absent from the DOM (`notice in DOM: 0`, same status line). The same spec
passes **8/8** against the containerised runtime. It is an artefact of the host-served
measurement harness (locality lookup), outside this plan's file set.

## Verification

| Gate | Result |
|---|---|
| `npx jest` (full) | **109 suites / 1049 tests, 0 failures** (baseline 106/1008 → exactly +3 suites, +41 tests) |
| `npm run build` | **rc=0**, `✓ Compiled successfully`, zero type errors |
| `npm run lint` | **0 errors**, 28 warnings — all pre-existing, **zero in my files** (grep for `consent` in lint output = 0) |
| Task 1 structural verify | ack key defined **exactly once**; `SHIPPED_CATEGORIES` and `isAllowed` present |
| Task 2 structural verify | 8/8 required strings present, **7/7 forbidden strings absent** |

Note on the lint figure: eslint's final line reports the *fixable* count, not the verdict —
the verdict is `0 errors`.

## Self-Check: PASSED

All seven created files exist on disk; all six commits (`7cc2bf44`, `68eb5777`, `c9e191f8`,
`1553d370`, `d994dd10`, `f88f157f`) are present in `git log`.
