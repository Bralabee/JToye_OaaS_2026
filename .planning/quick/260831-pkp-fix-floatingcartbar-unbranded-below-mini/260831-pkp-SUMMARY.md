---
phase: quick/260831-pkp-floatingcartbar-branded-below-min
plan: 01
subsystem: frontend-storefront
tags: [storefront, floating-cart-bar, branding, jest, tdd]
requires: []
provides:
  - "Always-branded oxblood FloatingCartBar with amber active-voice shortfall sub-label"
  - "Jest regression suite for the bar's branded class, shortfall label, and label absence"
affects: []
tech-stack:
  added: []
  patterns: ["cart-drawer.test.tsx real-CartProvider localStorage seeding pattern reused"]
key-files:
  created:
    - frontend/app/shop/__tests__/floating-cart-bar.test.tsx
  modified:
    - frontend/app/shop/[slug]/shop-detail-client.tsx
    - docs/metrics.json
    - README.md
    - AGENTS.md
    - CLAUDE.md
decisions:
  - "Bar is ALWAYS bg-oxblood hover:bg-oxblood-700 (locked by plan; grey below-minimum state removed)"
  - "Shortfall signal moved entirely into the sub-label: text-amber-300, active-voice 'Add £X.XX to order'"
  - "FloatingCartBar exported solely for the Jest suite (comment records this); no call-site change"
metrics:
  duration: "~5 minutes"
  completed: "2026-08-31"
  tasks: 3
  commits: 3
---

# Quick Task 260831-pkp: FloatingCartBar Branded Below-Minimum Summary

Below-minimum FloatingCartBar is now always branded oxblood with an amber "Add £1.01 to order" shortfall label, proven RED-then-GREEN by a new 4-test Jest suite.

## Commits

| Task | Name | Commit | Files |
| ---- | ---- | ------ | ----- |
| 1 | RED — export + regression suite, recorded failing | `26d5d4b9` | shop-detail-client.tsx (export only), floating-cart-bar.test.tsx |
| 2 | GREEN — locked styling change | `03f06ebb` | shop-detail-client.tsx |
| 3 | Docs-freshness regeneration | `aa2922fc` | docs/metrics.json, README.md, AGENTS.md, CLAUDE.md |

## What Changed

`frontend/app/shop/[slug]/shop-detail-client.tsx` (FloatingCartBar):
- Link className collapsed to a static string — `bg-oxblood hover:bg-oxblood-700` always; the `bg-slate-700 hover:bg-slate-800` below-minimum branch is gone. All other classes byte-identical.
- Sub-label: `text-xs text-slate-300` "Min {formatPrice(minimumOrderPennies)}" → `text-xs text-amber-300` "Add {formatPrice(minimumOrderPennies - totalPennies)} to order". Still gated on `belowMinimum`.
- `function FloatingCartBar` → `export function FloatingCartBar` with a comment that the export exists for the Jest suite only.
- UNTOUCHED (verified by diff content): the `belowMinimum` boolean, the R-07 `barRef`/`useBottomChromeHeight` wiring, the `AnimatePresence`/`m.div` structure, and checkout's own gating (`git diff -- "frontend/app/shop/[slug]/checkout/page.tsx" | wc -l` → 0).

New suite `frontend/app/shop/__tests__/floating-cart-bar.test.tsx` (4 `it()` blocks, cart-drawer.test.tsx pattern: real CartProvider seeded via `localStorage` key `jtoye-cart-test-shop`, global framer-motion mock mounts the bar synchronously; `@/lib/public-api-client` stubbed inert because shop-detail-client.tsx imports it at module level — FloatingCartBar never calls it):
- (a) below minimum (899 vs 1000): link located by ROLE, className contains `bg-oxblood`, not `bg-slate-700`
- (b) shortfall label: `getByText("Add £1.01 to order")` with className containing `text-amber-300` (1000 − 899 = 101)
- (c) at/above minimum (quantity 2 → 1798): `queryByText(/to order/)` null, link still `bg-oxblood`
- (d) zero minimum: no shortfall label, link still `bg-oxblood`

## Evidence — Both Directions of Every Check (proof standards §1)

### Task 1 RED (suite vs the UNMODIFIED component) — rc=1

```
FAIL app/shop/__tests__/floating-cart-bar.test.tsx
  ✕ renders the branded oxblood bar even below the minimum (never the grey dead-state)
  ✕ shows the amber active-voice shortfall label with the computed amount
  ✓ renders no shortfall label at/above the minimum, bar still oxblood
  ✓ renders no shortfall label when the shop has no minimum

● (a): Expected substring: "bg-oxblood"
  Received string: "flex items-center justify-between rounded-2xl px-5 py-3.5
  shadow-lg transition-all active:scale-[0.98] bg-slate-700 hover:bg-slate-800 text-white"

● (b): Unable to find an element with the text: Add £1.01 to order
  [DOM dump showed: <p class="text-xs text-slate-300">Min £10.00</p> and £8.99 total]

Tests: 2 failed, 2 passed, 4 total   rc=1
```

Tests (c)/(d) passing at RED is expected — the old label also only rendered below minimum; (c)'s own fail direction was bracketed in Task 2.

### Task 2 GREEN (suite vs the fixed component) — rc=0

```
PASS app/shop/__tests__/floating-cart-bar.test.tsx
  ✓ all 4 tests
Tests: 4 passed, 4 total   rc=0
```

### Bracketed arm for test (c) (clean → arm → clean, §8)

- Committed RED state first (`26d5d4b9`) so the restore target was a committed state.
- ARM: seed quantity 2 → 1 (total 899, below minimum). Run: rc=1, test (c) alone failed:
  `expect(received).toBeNull() / Received: <p class="text-xs text-amber-300">Add £1.01 to order</p>`
- RESTORE: quantity back to 2. Closing clean run: rc=0, 9/9 across floating-cart-bar + use-bottom-chrome-height suites.
- Restore verified BY CONTENT: `rg -uu -c 'quantity: 2'` on the test file → `1` (the single expected occurrence, in test (c)'s seed); instrument control `rg -uu -c 'quantity: 3'` → no match, rc=1 (the count can fail).

### Hook suite unaffected

`hooks/__tests__/use-bottom-chrome-height.test.tsx`: 5 tests PASS in the same closing run (it mimics the bar "in miniature" and asserts no classes — unaffected, as the plan predicted).

### Docs gates — fail directions observed live, then green

- `scripts/docs-freshness.sh` BEFORE `--write`: rc=1 naming the exact drift — `jest_blocks 1579 → 1583, jest_files 145 → 146, total_logical_invocations 3568 → 3572`.
- After `scripts/docs-freshness.sh --write`: rc=0 ("metrics match source, total 3572").
- `scripts/check-doc-metrics.sh` BEFORE prose edits: rc=1 with 10 FAIL lines across README.md/CLAUDE.md/AGENTS.md (3568/1579/145 vs 3572/1583/146) — NOT a vacuous pass; the gate demonstrably fires on drift.
- After updating the three docs: rc=0, "all 37 prose metric claim(s) across 3 doc(s) match".

### E2E no-change finding (recorded per plan)

`rg -uu -n 'View basket|bg-slate-700|Min £' frontend/e2e/storefront-flows.spec.ts` → exactly two hits, both `View basket` (lines 86, 595 — matching the plan's verbatim locations, which is the positive control proving the search direction works). No e2e spec asserts the grey class or the old "Min £" label; no e2e change needed.

### Branch position

`git log HEAD..origin/main | wc -l` → 0 (branch not behind base, proof standards §3).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Worktree frontend had no node_modules**
- **Found during:** Task 1 (first Jest run)
- **Issue:** `npx jest` from the worktree pulled a registry jest@30.5.0 which then failed on `Cannot find module 'next/jest'` — the worktree checkout has no installed dependency tree.
- **Fix:** Symlinked the main checkout's installed tree: `frontend/node_modules -> /home/sanmi/IdeaProjects/JToye_OaaS_2026/frontend/node_modules`. Identical, already-installed dependency set; NO package-manager install of any new package (package.json/package-lock.json untouched). The symlink is gitignored and appears in no commit.
- **Files modified:** none (filesystem-only, outside git)
- **Commit:** n/a

No other deviations — plan executed exactly as written.

## Quality-Dimension Notes

- Web perf: class-string swap + one text node; no bundle/image change. N/A beyond that.
- SEO: bar is client-side basket chrome, not indexable content. N/A.
- Agent-readiness: no API surface touched. N/A.
- Security: per plan threat model — checkout enforcement (`checkout/page.tsx`) proven byte-untouched by empty diff; no dependency installed (T-pkp-SC accept holds).
- Client-persisted identity lifecycle: reads the cart store only; no write path touched. N/A.
- Falsifiability: every assertion above carries a recorded fail direction.

## Known Stubs

None — no placeholder values, empty data sources, or TODOs introduced.

## Threat Flags

None — no new network endpoints, auth paths, file access, or schema changes.

## Self-Check: PASSED

- `frontend/app/shop/__tests__/floating-cart-bar.test.tsx` — FOUND
- `frontend/app/shop/[slug]/shop-detail-client.tsx` contains `text-amber-300` and `export function FloatingCartBar` — FOUND
- Commits `26d5d4b9`, `03f06ebb`, `aa2922fc` — FOUND in `git log`
