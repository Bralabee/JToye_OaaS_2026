---
phase: quick/260831-pkp-floatingcartbar-branded-below-min
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/app/shop/[slug]/shop-detail-client.tsx
  - frontend/app/shop/__tests__/floating-cart-bar.test.tsx
  - docs/metrics.json
  - README.md
  - AGENTS.md
  - CLAUDE.md
autonomous: true
requirements: [QUICK-PKP-01]
user_setup: []

must_haves:
  truths:
    - "With a basket total BELOW the shop minimum (899 vs 1000), the floating bar renders bg-oxblood — branded from the customer's FIRST add, never bg-slate-700."
    - "In the below-minimum state the sub-label reads exactly 'Add £1.01 to order' (the computed shortfall, formatPrice(minimumOrderPennies - totalPennies)) in text-amber-300."
    - "At or above the minimum (and when minimumOrderPennies is 0), NO shortfall label renders — only the total."
    - "The belowMinimum boolean, AnimatePresence/motion behaviour, the R-07 useBottomChromeHeight ref wiring, and checkout's own belowMinimum gating are byte-for-byte untouched."
    - "Every new Jest assertion was observed FAILING against the unmodified component before the fix landed (recorded in SUMMARY.md, both directions)."
  artifacts:
    - path: "frontend/app/shop/__tests__/floating-cart-bar.test.tsx"
      provides: "Jest regression coverage of the bar's branded class, shortfall label, and label absence"
      contains: "bg-oxblood"
    - path: "frontend/app/shop/[slug]/shop-detail-client.tsx"
      provides: "Always-branded FloatingCartBar with amber shortfall sub-label"
      contains: "text-amber-300"
    - path: "docs/metrics.json"
      provides: "Regenerated test counts including the new it() blocks"
  key_links:
    - from: "frontend/app/shop/__tests__/floating-cart-bar.test.tsx"
      to: "frontend/app/shop/[slug]/shop-detail-client.tsx"
      via: "named export FloatingCartBar (test-enablement export, no behaviour change)"
      pattern: "export function FloatingCartBar"
    - from: "frontend/app/shop/__tests__/floating-cart-bar.test.tsx"
      to: "frontend/components/storefront/cart-provider.tsx"
      via: "real CartProvider seeded via localStorage key jtoye-cart-{slug} (cart-drawer.test.tsx pattern)"
      pattern: "CartProvider shopSlug"
---

<objective>
Fix the FloatingCartBar unbranded below-minimum first state (owner complaint 2026-08-31).

Diagnosis is ALREADY VERIFIED — do not re-derive. In `frontend/app/shop/[slug]/shop-detail-client.tsx` lines 833-854, the bar renders `bg-slate-700 hover:bg-slate-800` whenever `belowMinimum` and oxblood otherwise. With the seeded shop (£8.99 item, £10 minimum) the customer's FIRST basket interaction shows a grey, off-brand bar that reads as a dead control.

Design decision is LOCKED — do not re-litigate:
1. Bar is ALWAYS `bg-oxblood hover:bg-oxblood-700`.
2. Below-minimum signal moves entirely into the sub-label: grey `text-slate-300` "Min £X" becomes amber `text-amber-300` active-voice `Add {formatPrice(minimumOrderPennies - totalPennies)} to order`.

Purpose: branded, alive-looking first interaction; the shortfall communicated as a next action, not a dead state.
Output: two-line styling/label change + a new Jest suite proven to fail on the pre-fix tree + regenerated docs metrics.

Git: all work on a feature branch (e.g. `feature/floatingcartbar-branded-below-min`), never main (global policy).
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
@$HOME/.claude/skills/proof-standards/SKILL.md
</execution_context>

<context>
@frontend/app/shop/[slug]/shop-detail-client.tsx (lines 795-862 — FloatingCartBar)
@frontend/components/storefront/__tests__/cart-drawer.test.tsx (the mocking pattern to copy)
@frontend/jest.setup.js (global framer-motion mock: m.* passthrough + AnimatePresence passthrough — rows mount synchronously in jsdom)

Test-landscape facts (already searched — do not re-search):
- NO existing Jest test renders the real FloatingCartBar. `frontend/hooks/__tests__/use-bottom-chrome-height.test.tsx` only mimics its shape ("in miniature") and asserts no classes — unaffected by this change.
- `frontend/e2e/storefront-flows.spec.ts` touches the bar only via `text=View basket` (lines 86, 595). NO e2e spec asserts `bg-slate-700` or "Min £" — no e2e change needed; record this as checked in SUMMARY.md.
- Checkout's own belowMinimum gating lives in `frontend/app/shop/[slug]/checkout/page.tsx` lines 711-712, 1012-1029 — OUT OF SCOPE, must not change.
</context>

<interfaces>
Current component (frontend/app/shop/[slug]/shop-detail-client.tsx:806-862):

```tsx
function FloatingCartBar({ slug, minimumOrderPennies }: { slug: string; minimumOrderPennies: number }) {
  const { itemCount, totalPennies } = useCart()
  const barRef = useRef<HTMLDivElement>(null)
  useBottomChromeHeight(barRef)
  const belowMinimum = minimumOrderPennies > 0 && totalPennies < minimumOrderPennies
  // ... AnimatePresence > m.div(ref=barRef) > Link:
  //   className={`flex items-center justify-between rounded-2xl px-5 py-3.5 shadow-lg transition-all active:scale-[0.98] ${
  //     belowMinimum ? "bg-slate-700 hover:bg-slate-800" : "bg-oxblood hover:bg-oxblood-700"
  //   } text-white`}
  //   ... right column:
  //   <span className="text-sm font-bold">{formatPrice(totalPennies)}</span>
  //   {belowMinimum && (<p className="text-xs text-slate-300">Min {formatPrice(minimumOrderPennies)}</p>)}
}
```

CartProvider seeding (from cart-drawer.test.tsx, real provider, no mock):

```tsx
localStorage.setItem(`jtoye-cart-${SLUG}`, JSON.stringify({ shopSlug: SLUG, items: [
  { productId: "p-1", title: "Jollof Rice", pricePennies: 899, quantity: 1, imageUrl: null, category: "Mains" },
]}))
render(<CartProvider shopSlug={SLUG}><FloatingCartBar slug={SLUG} minimumOrderPennies={1000} /></CartProvider>)
```

`formatPrice(pennies)` in the same module returns `£${(pennies / 100).toFixed(2)}` → 101 pennies renders "£1.01".
</interfaces>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: RED — export FloatingCartBar and write the regression suite; record it failing against the unmodified styling</name>
  <files>frontend/app/shop/[slug]/shop-detail-client.tsx, frontend/app/shop/__tests__/floating-cart-bar.test.tsx</files>
  <behavior>
    - Test (a) below-minimum branding: seed one item at 899 pennies, minimumOrderPennies=1000 → the "View basket" link's className CONTAINS "bg-oxblood" and does NOT contain "bg-slate-700". Locate the link via `screen.getByRole("link")` (DOM-status trap: never a text search that a button label could satisfy) and assert on its className.
    - Test (b) shortfall label: same seed → `screen.getByText("Add £1.01 to order")` exists and its className contains "text-amber-300". Exact computed amount — 1000 − 899 = 101 → "£1.01".
    - Test (c) at/above minimum: seed quantity 2 (1798 ≥ 1000) → `screen.queryByText(/to order/)` is null AND the link still carries "bg-oxblood"; also a minimumOrderPennies=0 render shows no shortfall label.
  </behavior>
  <action>
    1. In shop-detail-client.tsx change `function FloatingCartBar` (line 806) to `export function FloatingCartBar`, with a one-line comment that the export exists for the Jest suite only — NO other change in this task. Do not touch the belowMinimum boolean, the R-07 barRef/useBottomChromeHeight wiring, or AnimatePresence.
    2. Create `frontend/app/shop/__tests__/floating-cart-bar.test.tsx` following the cart-drawer.test.tsx pattern exactly: real `CartProvider` wrapping the exported `FloatingCartBar`, cart seeded via `localStorage.setItem("jtoye-cart-" + SLUG, ...)` before render, relying on the global framer-motion mock from jest.setup.js so the AnimatePresence-gated bar mounts synchronously. Write the three behaviors above as separate `it()` blocks. If importing shop-detail-client.tsx trips an import-time side effect in jsdom (e.g. `@/lib/public-api-client`), add a `jest.mock("@/lib/public-api-client", () => ({ __esModule: true, default: {} }))` stub — FloatingCartBar never calls it, so the stub cannot mask behavior.
    3. FAIL DIRECTION (proof standards §1 — mandatory): run the suite against the still-unmodified styling and capture the output on one line: `cd frontend && out=$(npx jest app/shop/__tests__/floating-cart-bar.test.tsx 2>&1); rc=$?`. Expected: rc=1 with (a) failing because the class is `bg-slate-700` and (b) failing because the label reads "Min £10.00". Test (c) may pass here (the old label also only renders below minimum) — its own fail direction is bracketed in Task 2. Record the failing output verbatim for SUMMARY.md. If the suite PASSES at this step, STOP — the assertions are vacuous; fix the test, not the component.
    4. Commit the RED state on the feature branch (test + export only) so Task 2's bracket arms have a committed restore target (proof standards §8).
  </action>
  <verify>
    <automated>cd frontend && out=$(npx jest app/shop/__tests__/floating-cart-bar.test.tsx 2>&1); rc=$?; echo "$out" | tail -20; echo "rc=$rc"  # EXPECTED at this stage: rc=1, tests (a)+(b) failing on bg-slate-700 / "Min £10.00"</automated>
  </verify>
  <done>Suite exists, runs, and demonstrably FAILS against the unmodified component with the grey class and old label named in the failure output; RED state committed.</done>
</task>

<task type="auto">
  <name>Task 2: GREEN — apply the locked styling change; bracket test (c)'s fail direction</name>
  <files>frontend/app/shop/[slug]/shop-detail-client.tsx</files>
  <action>
    1. Line 833-837: collapse the conditional class into a static string — the Link is always `bg-oxblood hover:bg-oxblood-700` (keep every other class: flex/rounded-2xl/px-5/py-3.5/shadow-lg/transition-all/active:scale-[0.98]/text-white). The `belowMinimum` boolean itself stays — it still gates the sub-label.
    2. Lines 850-854: replace the sub-label with `{belowMinimum && (<p className="text-xs text-amber-300">Add {formatPrice(minimumOrderPennies - totalPennies)} to order</p>)}`.
    3. Run the suite: all three tests pass. Record the passing output beside Task 1's failing output.
    4. Bracket test (c)'s fail direction (clean → arm → clean, restore verified BY CONTENT): temporarily change test (c)'s seed quantity from 2 to 1 (total 899, below minimum) and re-run — test (c) MUST now fail because the shortfall label renders and `queryByText(/to order/)` is non-null. Record that failure, revert the seed to quantity 2, re-run to green, and verify the restore by content: `grep -c 'quantity: 2' frontend/app/shop/__tests__/floating-cart-bar.test.tsx` returns the expected count (never `git diff --stat`).
    5. Confirm the out-of-scope surfaces are untouched: `git diff --name-only` lists ONLY shop-detail-client.tsx and the new test file so far, and `git diff -- "frontend/app/shop/[slug]/checkout/page.tsx"` is empty. Confirm the hook suite still passes: `npx jest hooks/__tests__/use-bottom-chrome-height.test.tsx`.
  </action>
  <verify>
    <automated>cd frontend && out=$(npx jest app/shop/__tests__/floating-cart-bar.test.tsx hooks/__tests__/use-bottom-chrome-height.test.tsx 2>&1); rc=$?; echo "$out" | tail -15; echo "rc=$rc"  # EXPECTED: rc=0, both suites green; a FAILING run would name bg-slate-700 or a missing "Add £1.01 to order"</automated>
  </verify>
  <done>All three assertions green post-fix; test (c) shown capable of failing via the bracketed arm with the restore proven by content; checkout page and hook wiring byte-for-byte unchanged; both fail/pass outputs recorded for SUMMARY.md.</done>
</task>

<task type="auto">
  <name>Task 3: Docs-freshness regeneration and close-out</name>
  <files>docs/metrics.json, README.md, AGENTS.md, CLAUDE.md</files>
  <action>
    1. New `it(` blocks were ADDED, so the counts moved. Regenerate — NEVER hand-edit or do arithmetic (trap: the gate greps literal `it(`/`test(` blocks): `bash scripts/docs-freshness.sh --write`, then confirm the source-tree half is green: `out=$(bash scripts/docs-freshness.sh 2>&1); rc=$?` → rc=0.
    2. Run the prose half: `out=$(bash scripts/check-doc-metrics.sh 2>&1); rc=$?`. If rc≠0, update the quoted Jest counts in README.md, AGENTS.md, and CLAUDE.md to the exact values now in docs/metrics.json (e.g. "1579 Jest it/test blocks across 145 files" → the new block count and 146 files), re-run until rc=0. Fail direction for this gate is pre-established by the project (README drifted for months while the tree moved) — if it passes with rc=0 BEFORE the prose edit despite a changed metrics.json, treat that as the vacuous-pass signal and investigate rather than proceeding.
    3. Commit on the feature branch and confirm `git log HEAD..origin/main` is empty before opening the PR (proof standards §3). PR body via quoted heredoc/`-F` file — never an interpolating `-m` string.
    4. Write SUMMARY.md in `.planning/quick/260831-pkp-fix-floatingcartbar-unbranded-below-mini/` recording: both directions of every check (Task 1 RED output, Task 2 GREEN output, the bracketed (c) arm), the e2e no-change finding (storefront-flows.spec.ts asserts neither the grey class nor "Min £" — checked, verbatim locations 86/595), and the docs-metrics before/after counts.
  </action>
  <verify>
    <automated>out=$(bash scripts/docs-freshness.sh 2>&1); rc1=$?; out2=$(bash scripts/check-doc-metrics.sh 2>&1); rc2=$?; echo "freshness rc=$rc1 metrics rc=$rc2"  # EXPECTED: both 0; a FAILING run prints the drifted count and exits non-zero</automated>
  </verify>
  <done>Both docs gates rc=0 with the new counts; branch not behind origin/main; SUMMARY.md carries fail+pass evidence for every assertion.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Browser client rendering | Purely presentational Tailwind class + label change; no new input crosses any boundary |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-pkp-01 | Tampering | Checkout minimum-order enforcement | mitigate | The grey bar was NEVER the gate — checkout/page.tsx:1029 `disabled={submitting \|\| belowMinimum}` is, and Task 2 step 5 proves it byte-for-byte untouched (`git diff` empty on that file) |
| T-pkp-SC | Tampering | npm installs | accept | No new dependency is installed by this plan; package.json untouched |
</threat_model>

<verification>
- Targeted Jest suites only (floating-cart-bar + use-bottom-chrome-height) — full `npm test` and `npm run build` are explicitly NOT required for this quick task.
- Every assertion carries a recorded fail direction: (a)/(b) via the RED run on the unmodified tree, (c) via the bracketed below-minimum seed arm.
- Out-of-scope invariants proven unchanged by empty diffs: checkout gating, belowMinimum boolean, R-07 ref wiring, AnimatePresence structure.
- Docs gates `docs-freshness.sh` and `check-doc-metrics.sh` both rc=0 after `--write` regeneration.
</verification>

<success_criteria>
- Below-minimum first add renders a branded oxblood bar with the amber "Add £1.01 to order" shortfall label (899 vs 1000).
- At/above minimum and zero-minimum renders show no shortfall label; bar unchanged oxblood.
- New Jest suite committed with RED-then-GREEN evidence; e2e checked and recorded unaffected.
- docs/metrics.json regenerated; prose counts in README/AGENTS/CLAUDE match; both gates green.
</success_criteria>

<output>
Create `.planning/quick/260831-pkp-fix-floatingcartbar-unbranded-below-mini/260831-pkp-SUMMARY.md` when done.
</output>
