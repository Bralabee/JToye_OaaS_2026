---
phase: 31-consumer-safety-and-legal-floor
plan: 03
subsystem: ui
tags: [accessibility, wcag, axe, nextjs, app-router, metadata, seo, landmarks, skip-link]

# Dependency graph
requires:
  - phase: 19-full-frontend-overhaul
    provides: PublicShell / PublicHeader / PublicFooter — the shared public chrome this plan adds landmarks to
  - phase: 33-consumer-product
    provides: the OGL geo-attribution block in PublicFooter and scripts/check-geo-attribution.sh, which this plan's footer edit had to keep green
provides:
  - Keyboard skip link on every public route served through PublicShell, targeting a main landmark by id
  - PublicFooter column headings at level 2, closing axe heading-order on routes whose own content has no level-2 heading
  - /auth/signin exposes one main landmark, one level-1 heading, and all its content inside that landmark
  - /auth/signin serves its own title, description and canonical instead of the root default
affects: [31-17 (adds a Legal footer column — must also be level 2), 31-18 (re-measures the axe baseline and owns the final count)]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Skip link: the operator-pitch.tsx:70 class string reused verbatim, never re-invented, targeting <main> by id"
    - "Metadata for a client page route: a sibling layout.tsx that exports metadata and renders {children} and nothing else"
    - "Comments must not spell the markup tokens a verify counts — prose satisfies grep"

key-files:
  created:
    - frontend/components/public/__tests__/public-shell-landmarks.test.tsx
    - frontend/app/auth/signin/layout.tsx
  modified:
    - frontend/components/public/public-shell.tsx
    - frontend/components/public/public-footer.tsx
    - frontend/app/auth/signin/page.tsx
    - frontend/app/auth/signin/__tests__/page.test.tsx

key-decisions:
  - "CardTitle is NOT re-tagged to h1: it is a shared shadcn primitive rendering at level 3 across dozens of surfaces, so the heading is written out on the page with the identical resolved classes instead"
  - "/auth/signin gets the same landmark markup as PublicShell but NOT PublicShell itself, because PublicHeader's sign-in CTA points at that very page"
  - "The nine new sign-in assertions were added to the page's existing test file rather than a new one, because the acceptance criteria demanded assertions (title, preservation, containment) that did not exist anywhere"

patterns-established:
  - "Skip-link tests assert document ORDER, not presence: a link rendered after the header is present, announced, and useless"
  - "Every heading/landmark query is preceded by a positive non-vacuity control proving the subtree mounted"
  - "region is asserted as CONTAINMENT per element, not as the existence of a landmark"

requirements-completed: [LGL-02]

# Metrics
duration: 20min
completed: 2026-08-16
---

# Phase 31 Plan 03: Public-Shell Skip Link, Footer Heading Levels and the Vendor Sign-In Landmarks Summary

**A keyboard skip link on every PublicShell route, footer column headings moved to level 2, and `/auth/signin` given a main landmark, a single level-1 heading and its own title/canonical — closing 9 of the 15 measured declared-surface axe nodes plus A11Y-06, which axe cannot see at all.**

## Performance

- **Duration:** ~20 min
- **Started:** 2026-08-16T12:04:00Z
- **Completed:** 2026-08-16T12:24:04Z
- **Tasks:** 2 (plus 2 self-found fix commits)
- **Files modified:** 4 source + 2 test + 4 docs/manifest

## Accomplishments

- **A11Y-06 closed at source.** `PublicShell` now opens with a skip link as the first focusable element, targeting `<main id="main">`. This is invisible to axe — WCAG 2.4.1 Bypass Blocks has no automatable signature — so the gate 31-18 builds could never have closed it.
- **F-C closed.** `PublicFooter`'s two column headings moved from level 3 to level 2 with class strings untouched, closing `heading-order` on `/shop/signin` and `/legal` (2 of the 15 nodes).
- **F-D closed.** `/auth/signin` — the single worst measured surface, carrying 7 of the 15 nodes on its own — now exposes exactly one `main`, exactly one level-1 heading, and every element (card, escape links, trading disclosure) inside the landmark.
- **The stale root title is gone.** A new `app/auth/signin/layout.tsx` carries the route's own title, description and canonical, which is the only way to give a client page route metadata in the App Router.
- **Two unfalsifiable verify limbs found and fixed by running the fail direction**, not by review (see Deviations).

## Task Commits

1. **Task 1: Skip link, main landmark, footer heading level** — `81800efc` (feat)
2. **Task 2: /auth/signin shell, heading and title** — `108bc8ba` (feat)
3. **Fix: a comment satisfied the landmark grep on /auth/signin** — `c766ebb0` (fix)
4. **Fix: the same defect in PublicShell** — `f37cd5d5` (fix)

## Files Created/Modified

- `frontend/components/public/public-shell.tsx` — skip link as the first child, `id="main"` on the existing `<main>`. Still a plain server component (no client directive, no route-segment config), so the #89 force-dynamic/CSP-nonce contract is untouched.
- `frontend/components/public/public-footer.tsx` — two column headings moved to level 2; class strings, persona gating, OGL attribution and `CompanyLegalLine` untouched. A comment records that any column 31-17 adds takes level 2 too.
- `frontend/components/public/__tests__/public-shell-landmarks.test.tsx` — **new.** 4 assertions: skip link first in document order, hidden-until-focused, exactly one `main` whose id the href matches, footer headings at level 2 with zero at level 3 — each behind a non-vacuity control.
- `frontend/app/auth/signin/page.tsx` — skip link + `<main id="main">` wrapping the card AND the legal line; `CardTitle` replaced by an `h1` with the identical resolved classes; every existing element preserved.
- `frontend/app/auth/signin/layout.tsx` — **new.** Server component exporting `metadata` (title, description, `alternates.canonical`) and rendering `{children}` and nothing else.
- `frontend/app/auth/signin/__tests__/page.test.tsx` — 9 new assertions across landmarks, containment, preservation and metadata.
- `docs/metrics.json`, `README.md`, `CLAUDE.md`, `AGENTS.md` — Jest counts 944→957 blocks / 99→100 files, total 2807→2820. Both halves of the docs-freshness loop regenerated together.

## Recorded measurements the plan asked for

### `check-geo-attribution.sh` around the footer edit

| When | Exit | Output |
|---|---|---|
| Before the footer edit | **0** | `PASS: three attribution lines render and the year matches the committed dataset (2026)` |
| After the footer edit | **0** | identical PASS, all three rights holders present, year 2026 |

A VOID here would have read exactly like a missing footer, so all three exit states were proven reachable rather than assuming 0 meant anything (see break arms below).

### The served `<title>` on `/auth/signin`

| When | Value |
|---|---|
| Before | `J'Toye OaaS - Multi-Tenant Order Management` (inherited from `app/layout.tsx:20`; the route had no layout of its own) |
| After | `Vendor sign in — J'Toye`, with `alternates.canonical: "/auth/signin"` and a description naming the customer door in words |

## Break arms — both directions, real output

Every arm was run on a COMMITTED tree, restored by `git checkout --`, and verified by `git hash-object` against the committed blob. `git diff --stat` was never used to prove a restore (it is empty both when a file is restored and when it was never written). The closing clean arm ran LAST and is recorded at the end.

| # | Criterion | Deliberate break | Fail direction — real result | Clean direction |
|---|---|---|---|---|
| A | Skip link is reachable FIRST | moved the link below `<PublicHeader />` | **RED** — `expect(element).toHaveAttribute("href", "#main")` → `Received: href="/"` (the wordmark). The other 3 assertions stayed GREEN, which is the point: a presence-only assertion cannot see this bug | 4/4 pass |
| B | Footer headings are level 2 | reverted "For customers" to level 3 | **RED** — `expect(h2s.length).toBeGreaterThanOrEqual(2)` → `Received: 1`; and the grep limb went `<h3` 0 → **1** | 4/4 pass, `<h3` 0 |
| C1 | The footer still satisfies the OGL gate | deleted the Royal Mail attribution line | **exit 1** — `FAIL: the footer does not name 1 required rights holder(s): Royal Mail data` | exit 0 |
| C2 | …and a VOID is distinguishable from a pass | parked `public-footer.tsx` out of the tree | **exit 2** — `VOID: footer component not found` | exit 0, restore hash-verified `543b4282` |
| D | `/auth/signin` has a main landmark | replaced `<main>` with `<div>` | **RED** — 2 assertions: `getAllByRole('main')` and the per-element containment arm. Grep limb `<main` also went 1 → **0** after the fix below | 18/18 pass |
| E1 | …and its own title | parked `layout.tsx` | suite failed to LOAD (`Cannot find module '../layout'`, `Tests: 0 total`) — a weaker signal, so a sharper arm was run | file-existence limb correctly reported FAIL |
| E2 | …and its own title (sharpened) | set the layout title back to the root default string | **RED** on exactly the two title arms — `Expected pattern: /vendor sign in/i` / `Expected pattern: not /Multi-Tenant Order Management/i`, both `Received: "J'Toye OaaS - Multi-Tenant Order Management"`. The canonical arm stayed GREEN, isolating the failure | 18/18 pass |
| F | `npm run build` is a real type-check gate | typed `alternates: { canonical: 12345 }` | **exit 1** — `Type error: Type 'number' is not assignable to type 'string \| URL \| AlternateLinkDescriptor \| null \| undefined'`, after `Running TypeScript ...` in the log | exit 0, `Running TypeScript` present |
| G | Nothing was dropped from the sign-in page | deleted the `/shop/signin` cross-link | **RED** on 3 assertions including the preservation arm. The plan's grep limb `/shop/signin >= 1` stayed **GREEN** (comment-only match) — recorded as vacuous, jest is the strong form | 18/18 pass |
| H | `PublicShell` targets its landmark by id | removed `id="main"` from `<main>` | **RED** — the landmark arm; grep limb went 1 → **0** after the fix below | 4/4 pass |

### Closing clean arm (run last)

```
=== content identity vs the committed blobs ===
OK   components/public/public-shell.tsx  b8d3574127fece1a01a95d59131e0a5d59e4f100
OK   components/public/public-footer.tsx  543b4282f14201db573e2d55572cf1296b984faf
OK   app/auth/signin/page.tsx  c16a942c9c5a555e99b33b3e84db4f94aeeb2ab1
OK   app/auth/signin/layout.tsx  90a94186c71ce7cb3053bc117198df6e8069f43d
OK   components/public/__tests__/public-shell-landmarks.test.tsx  94db398974102f893f0a00e6417dd256f18d9a52
OK   app/auth/signin/__tests__/page.test.tsx  b28d38da617d26302e6e42a3d2effb543df1c14d

Test Suites: 100 passed, 100 total
Tests:       957 passed, 957 total

shell id=main : 1   shell href=#main : 1   shell focus:not-sr-only : 1   shell 'use client' : 0
footer <h3 : 0      footer <h2 : 2
signin <h1 : 1      signin <main : 1       signin href=#main : 1        layout 'use client' : 0

check-geo-attribution.sh exit=0    docs-freshness.sh exit=0    check-doc-metrics.sh exit=0
npm run build exit=0 (with "Running TypeScript" in the log)    npm run lint exit=0 (0 errors)
```

## Unfalsifiable criteria found — declared, not silently substituted

The project contract requires these to be named rather than quietly replaced.

1. **`grep -cF '<main' app/auth/signin/page.tsx >= 1` was UNFALSIFIABLE as first written.** Break arm D deleted the landmark and the limb still counted 1, because the file's own header comment spelled the tag out. Fixed in `c766ebb0`: the markup tokens are now described in words in the prose and written out exactly once in the markup, so the limb measures markup and nothing else — re-run of arm D then took it to 0. **Strictly stronger form:** the jest assertion `getAllByRole('main')` plus per-element containment, which failed in BOTH runs of arm D.
2. **The same defect in `public-shell.tsx`** (`id="main"` 2, `focus:not-sr-only` 2, one of each in prose). Fixed in `f37cd5d5`; arm H then took `id="main"` to 0. Strictly stronger form: the same jest assertions.
3. **`grep -cF '/shop/signin' >= 1` remains vacuous by nature** — the page's header comment legitimately names the customer counterpart, and removing that sentence would cost more than the limb is worth. Recorded, not fixed. The preservation criterion is carried by the jest arm, proven red in arm G.
4. **Instrument defect in my own clean-arm script** (not in the product): a `grep -cF 'href=\"#main\"'` inside single quotes searched for literal backslashes and printed `0`. Re-measured with correct quoting: 1. Recorded because a `0` from a broken instrument is indistinguishable from a real absence — suspect the instrument first.

## Decisions Made

- **`CardTitle` was not re-tagged.** The plan allowed either promoting `CardTitle` to `h1` or writing the heading out. `CardTitle` renders at level 3 and is a shared shadcn primitive; re-tagging it would have changed every card in the product to fix one page. The heading is written out with the exact resolved class list (`text-2xl font-bold leading-none tracking-tight text-oxblood` — the `cn`/tailwind-merge output of the original), so this is a semantic change with no visual one.
- **`/auth/signin` keeps its own surface.** Same landmark markup as `PublicShell`, not `PublicShell` itself — its header's sign-in CTA points at this very page.
- **Metadata names the customer door in words, never realm ids** (T-31-03-01). An assertion pins this: the description must not match `/jtoye-dev|jtoye-customers|keycloak|realm/i`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added the nine assertions Task 2's acceptance criteria require**
- **Found during:** Task 2
- **Issue:** Task 2's `files_modified` listed only `page.tsx` and `layout.tsx`, and its `<verify>` block was greps + build + lint. But its acceptance criteria demand assertions on the rendered title, on landmark containment, and on preservation of every load-bearing element — none of which existed, and none of which a grep can make. Left as-is, the criteria would have been satisfied only by limbs already shown to be vacuous.
- **Fix:** Extended the page's existing `__tests__/page.test.tsx` with 9 assertions across four describes (landmarks, containment, preservation, metadata).
- **Files modified:** `frontend/app/auth/signin/__tests__/page.test.tsx`
- **Verification:** Arms D, E2 and G each turn a different subset of them red.
- **Committed in:** `108bc8ba`

**2. [Rule 1 - Bug] Two verify limbs could not fail because comments satisfied them**
- **Found during:** Break arm D (Task 2), then applied to Task 1's file
- **Issue:** Prose in a source file counts toward `grep -cF`. `<main` in `page.tsx` and `id="main"` / `focus:not-sr-only` in `public-shell.tsx` each appeared once in a comment, so those limbs passed on a deliberately broken tree.
- **Fix:** Markup tokens described in words in the prose, written out exactly once in the markup. Both files carry a comment saying so, so the next editor does not reintroduce it.
- **Files modified:** `frontend/app/auth/signin/page.tsx`, `frontend/components/public/public-shell.tsx`
- **Verification:** Re-run of arms D and H took the limbs to 0.
- **Committed in:** `c766ebb0`, `f37cd5d5`

**3. [Rule 3 - Blocking] Regenerated `docs/metrics.json` and the three prose docs**
- **Found during:** Both tasks
- **Issue:** New test blocks make `docs-freshness.sh` red, and regenerating the manifest alone makes `check-doc-metrics.sh` red — the two gates are one loop.
- **Fix:** `scripts/docs-freshness.sh --write` plus the matching numbers in `README.md`, `CLAUDE.md` and `AGENTS.md`. 944→957 blocks, 99→100 files, 2807→2820 total.
- **Verification:** Both gates exit 0.
- **Committed in:** `81800efc`, `108bc8ba`
- **⚠ Merge note for the orchestrator:** 31-01 and 31-02 add test files in parallel worktrees and will produce conflicting `docs/metrics.json` and prose numbers. Resolve by re-running `scripts/docs-freshness.sh --write` on the merged tree and then correcting the three docs — never by arithmetic on the conflicting values.

---

**Total deviations:** 3 auto-fixed (1 missing critical, 1 bug, 1 blocking)
**Impact on plan:** No scope creep. Deviation 1 is what makes the plan's own acceptance criteria checkable; deviation 2 is a falsifiability defect caught by running the fail direction; deviation 3 is a required CI loop.

## Issues Encountered

- **The worktree had no `frontend/node_modules`.** A symlink to the main checkout's copy worked for jest but Turbopack refused it outright (`Symlink [project]/node_modules is invalid, it points out of the filesystem root`) — so `npm run build`, the type-check gate, could not run. Resolved with `cp -al` (hardlink copy, ~700 MB, no extra disk). No package was installed and no lockfile touched. The directory is gitignored and is not in any commit.
- **Break arm E1 failed at suite-load rather than at the assertion** (`Cannot find module '../layout'`, `Tests: 0 total`). "The suite could not load" is not the same evidence as "the title assertion fired", so arm E2 was added to isolate it. Recorded because a suite that fails to load reports zero tests, which is the same shape as a suite that ran nothing.
- **The shared scratchpad is genuinely shared:** a sibling executor (plan 31-04) overwrote a scratch file of mine mid-run. All later scratch files were prefixed `31-03-`.

## Known Stubs

None. No placeholder text, empty data paths or unwired components were introduced.

## Threat Flags

None. No new network endpoint, auth path, file access pattern or schema change. The two mitigations this plan owns were implemented and asserted:
- **T-31-03-01** — the new metadata names the customer door in words; an assertion forbids realm ids, IdP hostnames and the word "realm" in the description.
- **T-31-03-04** — `PublicShell` remains a plain server component (`'use client'` count 0), keeping the #89 CSP-nonce/force-dynamic property.
- **T-31-03-03** — `check-geo-attribution.sh` was run before and after the footer edit and its VOID exit was proven reachable, so exit 0 is a real result.

## Verification Not Performed

- **No browser/axe re-measurement.** The node counts quoted here are the plan's 2026-08-15 baseline, whose declared validity window ends 2026-08-22. This plan changed markup that should close 9 of the 15 nodes, but **it did not re-run axe**, and a naive axe count is a known artefact source in this repo. 31-18 re-measures and is the authority on the final count.
- **No screenshot.** The visual claim made here is narrow and structural — identical resolved class lists, unchanged class strings — not "it looks right".
- **No container rebuild / E2E.** Nothing in this plan touches a runtime image; the runtime-parity gate belongs to the phase's E2E step.

## User Setup Required

None.

## Next Phase Readiness

- **31-17 must use level 2** for the "Legal" footer column it adds; the footer carries a comment saying so, and `public-shell-landmarks.test.tsx` asserts zero level-3 headings in the footer, so a level-3 column will go red immediately.
- **31-18** owns the re-measurement. Expected direction of travel: `landmark-one-main`, `page-has-heading-one` and `region:5` gone from `/auth/signin`, `heading-order` gone from `/shop/signin` and `/legal`. A11Y-06 is closed at source and will not appear in its count either way.
- The storefront shell (`app/shop/layout.tsx`) still has no skip link — UI-SPEC names both shells and this plan's scope was `PublicShell`.

## Self-Check: PASSED

All 6 claimed source/test files exist on disk; all 5 claimed commits resolve
(`git cat-file -e`). The commit range `64d9f0ad..HEAD` touches 11 files and
neither `.planning/STATE.md` nor `.planning/ROADMAP.md` is among them, nor any
of the 9 files owned by the parallel plans 31-01 and 31-02. Working tree clean.

---
*Phase: 31-consumer-safety-and-legal-floor*
*Completed: 2026-08-16*
