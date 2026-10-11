---
phase: 37-real-world-operations-readiness
plan: 12
subsystem: ui
tags: [finance, per-shop-scope, csv-export, ledger-reversal, void-order, d-10, rwo-027, rwo-018, nextjs, jest]

requires:
  - phase: 37-11
    provides: "GET /financial-transactions/summary|list|export.csv?shopId&from&to with the scope echo (FinanceScopeDto), Content-Disposition + X-Finance-Scope exposed via CORS, entryKind/reversesTransactionId/shopId on rows"
  - phase: 37-10
    provides: "POST /orders/{id}/void {reason} -> 200 OrderDetailDto (voidedAt/voidedBy/voidReason); 409 order-already-voided (voidedBy, voidedAt); voided fields on OrderDto"
  - phase: 37-09
    provides: "staff/me via the shell's ShopSwitcherProvider (groupAdmin, userId)"
  - phase: 37-06
    provides: "ConfirmActionDialog shape with a required cancelLabel"
provides:
  - "frontend/lib/finance-api.ts: fetchFinanceSummary/fetchFinanceTransactions/fetchFinanceExport with shopId/from/to, the echoed scope, Content-Disposition filename parsing, formatSignedPennies (U+2212), financeGate"
  - "/dashboard/finance: API-echoed scope banner, Choose-a-shop state for a non-admin on All shops (no request), Export CSV (outline h-11, Download) of the echoed scope under the server's filename, inline export failure alert, empty-range note, Reversal badge + 'Reversal of ORD-...' + signed amounts, Voided badge on the reversed sale"
  - "Dashboard home finance tile scoped by the same rule, footnoted with the banner wording"
  - "VoidOrderDialog (components/dashboard/orders/void-order-dialog.tsx) and its wiring in OrderDetailPanel; VoidedBadge shared by detail, Orders list and finance"
  - "useOptionalShopSwitcherData(): the shell's staff/me answer, or null outside the provider"
affects: [37-15-runtime-gate, dashboard-finance, dashboard-orders, order-detail]

actuals:
  tokens: 17588
  tasks: 2
  commits: 5
plan_head_before: 0462bf80316509748fcbea6a791119e417885261
plan_head_after: d659795a4b1d6ef8a18f3f22d03e319abb3980fa

tech-stack:
  added: []
  patterns:
    - "A scoped read returns the scope the server filtered on, and every label and follow-up action (banner, export) reads that echo, never the client's own selection"
    - "A page that can decide better with the shell's access answer reads it through an OPTIONAL context hook; outside the provider it decides nothing and the server stays the boundary"
    - "Only the newest load may write state (a sequence ref), because the shop context hydrates after mount and can change mid-flight"
    - "Negative money carries a real minus sign (String.fromCharCode(0x2212)), never colour alone"

key-files:
  created:
    - frontend/lib/finance-api.ts
    - frontend/components/dashboard/finance-scope-text.tsx
    - frontend/components/dashboard/orders/voided-badge.tsx
    - frontend/components/dashboard/orders/void-order-dialog.tsx
    - frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx
    - frontend/components/dashboard/orders/__tests__/void-order-dialog.test.tsx
    - .planning/phases/37-real-world-operations-readiness/evidence/37-12-red-finance-scope.json
    - .planning/phases/37-real-world-operations-readiness/evidence/37-12-red-void-order.json
  modified:
    - frontend/app/dashboard/finance/page.tsx
    - frontend/app/dashboard/page.tsx
    - frontend/components/dashboard/orders/OrderDetailPanel.tsx
    - frontend/app/dashboard/orders/page.tsx
    - frontend/app/dashboard/orders/[id]/page.tsx
    - frontend/components/dashboard/shop-switcher-provider.tsx
    - frontend/types/api.ts

key-decisions:
  - "37-12: finance surfaces show the scope the API ECHOED (summary.scope), never the switcher; the export replays that echo, so the file is exactly what is shown (T-37-33)"
  - "37-12: a non-admin on All shops is not sent tenant totals at all (financeGate from the shell's staff/me): Choose a shop; tenant-wide STAFF keeps Admin access required; outside the provider the server decides, as before"
  - "37-12: who voided an order reads 'you', the staff-directory name (Group admin viewers only) or 'a Group admin', never the raw subject id the server stores"
  - "37-12: a finance SALE is marked Voided only when its REVERSAL is on the same page (no voided flag on the ledger DTO); recorded as WINDOWS #26"

patterns-established:
  - "Say is not data: a scope banner is built from the response, and its disagreement with the client is a test arm"

requirements-completed: [RWO-027, RWO-018]

coverage:
  - id: D1
    description: "Finance sends the switcher's shop as ?shopId=; the banner reads the scope the API echoed ('Showing {Shop} only' / 'Showing all shops') and, on disagreement, the API's scope wins; long shop names wrap; a scope change reloads"
    requirement: "RWO-027"
    verification:
      - kind: unit
        ref: "frontend/app/dashboard/finance/__tests__/finance-scope.test.tsx#Finance scope banner (6 tests): RED at 62bedf6a (RED_EVIDENCE_OK, evidence/37-12-red-finance-scope.json), GREEN at 81d4f105; break arm A (banner from the switcher) red on the disagreement, echoed-name, export-echo and long-name arms"
        status: pass
    human_judgment: false
  - id: D2
    description: "A shop manager on All shops sees 'Choose a shop to see its figures' and no finance request is made; STAFF keeps 'Admin access required' (server 403 with a shop, or tenant-wide STAFF with none)"
    requirement: "RWO-027"
    verification:
      - kind: unit
        ref: "finance-scope.test.tsx#Who sees which figures (3 tests); break arm B (gate returns fetch) red on the manager arm and the home-tile arm"
        status: pass
    human_judgment: false
  - id: D3
    description: "Reversal rows: outline 'Reversal' badge, 'Reversal of ORD-...' reference (wraps), U+2212 minus ('−£24.50'); the reversed sale keeps a 'Voided' badge; no badge without a reversal"
    requirement: "RWO-027"
    verification:
      - kind: unit
        ref: "finance-scope.test.tsx#Reversal rows (2 tests); break arm C (Intl hyphen) red; break arm D (every SALE Voided) red on the absence test"
        status: pass
    human_judgment: false
  - id: D4
    description: "Export CSV (outline, h-11) requests export.csv with the echoed scope and range as a blob and saves it under the server's Content-Disposition filename; failure is an inline role=alert plus a toast; an empty range still downloads and says 'No transactions in this range'"
    requirement: "RWO-027"
    verification:
      - kind: unit
        ref: "finance-scope.test.tsx#Export CSV (4 tests)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Dashboard home finance tile follows the same scope rule with the banner wording as its footnote"
    requirement: "RWO-027"
    verification:
      - kind: unit
        ref: "finance-scope.test.tsx#Dashboard home finance tile (3 tests); app/dashboard/__tests__/page.test.tsx and joined-toast.test.tsx green"
        status: pass
    human_judgment: false
  - id: D6
    description: "Group admin + CONFIRMED/PREPARING/READY/COMPLETED + not voided -> outline 'Void order' with Ban; shop manager, DRAFT/PENDING and already-voided -> none; no Delete control"
    requirement: "RWO-018"
    verification:
      - kind: unit
        ref: "frontend/components/dashboard/orders/__tests__/void-order-dialog.test.tsx#Who is offered 'Void order' (9 tests): RED at e2b329ed (RED_EVIDENCE_OK, evidence/37-12-red-void-order.json), GREEN at d659795a; arm F (button and dialog for every role) red on the manager arm; arm G (no status gate) red on DRAFT and PENDING; arm H (no voided gate) red on two arms"
        status: pass
    human_judgment: false
  - id: D7
    description: "VoidOrderDialog: title 'Void order {number}?', body, required reason (aria-required, 3 rows), destructive 'Void order' disabled until non-whitespace text beside the permanent hint, 'Keep order', 'Working…' and no close mid-flight, card no-refund sentence, 409 inline alert with name and date, other failures a toast"
    requirement: "RWO-018"
    verification:
      - kind: unit
        ref: "void-order-dialog.test.tsx#VoidOrderDialog (8 tests); arms I (untrimmed), J (sentence always), M (close mid-flight), N (raw voidedBy in the 409) each red on their own arm"
        status: pass
    human_judgment: false
  - id: D8
    description: "After a void: Voided badge and 'Voided by {name} on {date, time} · Reason: {reason}' in the detail, the order still open; the name is never a raw id; a 1,000-character reason wraps (375px backstop by class); the Orders list row shows the same badge"
    requirement: "RWO-018"
    verification:
      - kind: unit
        ref: "void-order-dialog.test.tsx#The voided block (4 tests) and #Orders list (1 test); arm K (truncate) and arm L (no list badge) each red"
        status: pass
    human_judgment: false
  - id: D9
    description: "Goods kept: pending-badge-contrast, OrderDetailPanel.test, RefundDialog.test, orders page tests, dashboard page tests, a11y-axe; full jest, lint and build green"
    verification:
      - kind: unit
        ref: "npx jest --ci: 202 suites, 2353 tests, 0 failed; pending-badge-contrast arm E (bg-yellow-500) red then restored"
        status: pass
      - kind: other
        ref: "npm run lint (0 errors, 32 warnings, none new); npm run build (TypeScript ran, fresh BUILD_ID, new copy present in .next)"
        status: pass
    human_judgment: false
  - id: D10
    description: "The rebuilt runtime shows all of the above against the 37-10/37-11 server (live browser)"
    verification: []
    human_judgment: true
    rationale: "The shared compose runtime predates 37-10/37-11 and is rebuilt at 37-15; a live run now would exercise old backend code (WINDOWS #25)."

duration: 16 min
completed: 2026-10-09
status: complete
---

# Phase 37 Plan 12: Per-shop finance UI, CSV export and order void Summary

**`/dashboard/finance` now shows a shop manager their own shop's figures, labelled with the scope the API says it filtered on rather than the switcher's own value, and exports exactly that scope under the server's filename. Reversal rows carry a badge, the "Reversal of ORD-…" reference and a real minus sign. A Group admin can void a CONFIRMED-or-later order from its detail with a required reason, and the void shows wherever the order appears.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-10-09T00:13:09Z
- **Completed:** 2026-10-09T00:29:31Z
- **Tasks:** 2 (Task 1 tracer, Task 2 auto, both tdd)
- **Files modified:** 15 (13 frontend + 2 RED evidence records)

## Accomplishments

- **UXT-027 on screen.** A shop manager no longer reads "No financial data yet". With a shop selected they see that shop's figures under "Showing **{Shop}** only". On All shops they see "Choose a shop to see its figures", and the browser does not even ask for tenant totals.
- **Say is not data (T-37-33).** The banner reads `summary.scope` from the response. When the API's scope disagrees with the switcher, the page shows the API's scope, and the export replays that echo too. The disagreement arm was red against a switcher-derived banner.
- **Reversals read as reversals.** A REVERSAL is an outline "Reversal" badge, the server's "Reversal of ORD-…" and `−£24.50` with U+2212. The sale it reverses stays listed with the same "Voided" badge the order shows. Every amount on the page goes through the signed formatter.
- **Void with a record (D-10).** "Void order" appears only for a Group admin, on CONFIRMED-or-later orders that are not voided, and no Delete control appears. The dialog needs a reason, says plainly that a card void does not refund, and turns a second void with a different reason into an inline "already voided by {name} on {date}". Afterwards the detail and the Orders list both show "Voided", and the detail adds who, when and why.

## Task Commits

1. **Task 1: Finance shows the API's scope, per-shop figures, reversal rows and an export** (tracer, tdd)
   - `62bedf6a` test(37-12): failing arms (RED 16/18, RED_EVIDENCE_OK)
   - `81d4f105` feat(37-12): per-shop finance with the scope banner, reversal rows and CSV export (GREEN)
   - `dea57659` refactor(37-12): spell the minus sign as the U+2212 code point (19/19 after)
   - Tracer gate: `HUMAN_VERIFY_MODE` end-of-phase with an automated-only verify. `npm --prefix frontend test -- app/dashboard/finance` was re-run after the refactor: 2 suites, 19 passed, rc=0. Expansion continued.
2. **Task 2: Void an order from its detail, and show voids everywhere the order appears** (tdd)
   - `e2b329ed` test(37-12): failing arms (RED 18/22, RED_EVIDENCE_OK)
   - `d659795a` feat(37-12): void with a required reason, voids shown in the list (GREEN)

**Plan metadata:** the docs(37-12) commit that adds this file.

## TDD Gate Compliance

| Task | RED | GREEN | REFACTOR |
|------|-----|-------|----------|
| 1 | `62bedf6a` (16/18, TAP, `evidence/37-12-red-finance-scope.json`, RED_EVIDENCE_OK) | `81d4f105` | `dea57659` |
| 2 | `e2b329ed` (18/22, TAP, `evidence/37-12-red-void-order.json`, RED_EVIDENCE_OK) | `d659795a` | none needed |

- **Task 1 RED.** Target: "shows the API's scope when it disagrees with the switcher". Actual: no `finance-scope-banner` element. The page had no banner and sent no `shopId`. **Semantic assessment:** the target ran and failed on its planned assertion. The other 15 reds are the planned behaviour being absent: no banner (×6), no Export CSV (×4), no tile footnote (×3), no Choose-a-shop state, and no Reversal badge. The tenant-wide STAFF arm was red because the old page fetched and showed the summary. The suite loaded the old page inside the real provider, and 2 tests passed. One is the STAFF+shop refusal, a good that must hold both before and after. The other is the no-reversal absence test, which is vacuous before GREEN; its fail direction is arm D.
- **Task 2 RED.** Target: "a Group admin sees an outline 'Void order' with the Ban icon on a COMPLETED order". Actual: `Unable to find role="button" and name "Void order"`. **Semantic assessment:** the target ran and failed on its planned assertion. All 18 reds are absences of the planned behaviour. The 4 passing tests are absence assertions (manager, DRAFT, PENDING, already voided) that are vacuously true on a panel with no void at all; arms F, G and H supply their fail direction.

## Acceptance criteria, both directions

| Criterion | Pass | Fail |
|-----------|------|------|
| T1: scope-disagreement arm red against a switcher-derived banner, green against the API-derived one | GREEN 18/18 (`81d4f105`) | Arm A (banner and export scope built from `contextShopId` and the switcher's shop list): 4 red. These were the disagreement, echoed-name, export-echo and long-name arms. RED run: 16/18 |
| T1: `pending-badge-contrast.test.tsx` stays green | 1/1 in every finance run | Arm E (`bg-yellow-500`): 1/1 red |
| T1: manager on All shops requests nothing | `financeCalls()` is `[]` | Arm B (gate returns "fetch"): finance page and home tile arms red |
| T1: real minus sign | `−£24.50` present and `-£24.50` absent | Arm C (Intl hyphen-minus): reversal arm red. The first attempt at arm C did not apply, because the source held the literal character, not an escape. Its rc=0 run was **not** counted; it was rerun after `dea57659` and went red |
| T1 `<verify>` (`npm --prefix frontend test -- app/dashboard/finance`, fails on non-zero or "Tests: 0 total") | rc=0, 19 passed | RED run rc=1 |
| T2: each behaviour asserted; the role-gate arm red against a dialog shown to every role | GREEN 22/22 (`d659795a`) | Arm F (button **and** dialog ungated): exactly "a shop manager is not offered it" red |
| T2: OrderDetailPanel.test and RefundDialog.test stay green | 99/99 across `components/dashboard/orders` and `app/dashboard/orders` | Arms G to N each red only on their own arm (table in coverage D6-D8) |
| T2 `<verify>` build | `npm run build` rc=0, "Running TypeScript … Finished in 6.4s", BUILD_ID 01:28:27 BST, new copy found in `.next` (3 and 6 files; control string rc=1) | `tsc --noEmit` with an injected `number = "string"`: rc=2, `TS2322` (tests are type-checked by bare tsc; next build uses `tsconfig.build.json`) |

The break arms ran bracketed (clean → arms → clean), each against committed code. Restores were verified by sha256: Task 1 `750ab985…` / `0503d705…`, and later `daea0568…` after the refactor; Task 2 used three files. The closing runs went 18/18, 19/19 and 99/99.

## Verification

- Plan `<verification>`: the finance and orders jest suites are green with fail arms, and `npm run build` is green.
- **Full frontend gates** (after the last task):
  - `npm run lint`: rc=0, 0 errors, 32 warnings. The only warning in a touched file is the existing `watch("shopId")` React-Compiler note, which was line 334 at `0462bf80` and is line 335 now.
  - `npx jest --ci`: **202 suites, 2353 tests, 0 failed**. That is the 37-09 baseline of 2313 plus this plan's 18 + 22. 37-10 and 37-11 touched no frontend file (`git diff 7522879..0462bf80 -- frontend` is empty), so the delta reconciles exactly.
  - `npm run build`: rc=0.
- T-37-SC: `git diff 0462bf80..HEAD -- frontend/package.json frontend/package-lock.json` is 0 bytes. The same range form on `types/api.ts` is 2852 bytes, so the check can see a change.

## Decisions Made

- **Finance gate** (`financeGate`). A shop is selected → fetch, and the server checks SHOP_MANAGER. All shops: a Group admin fetches. A non-admin is not sent tenant totals at all, so the page asks for nothing ("Choose a shop"). Tenant-wide STAFF keeps "Admin access required". With access unknown (no provider, or staff/me unreadable), the server decides exactly as before 37-12. That is why the existing tests, which render with no provider, are unchanged.
- **The export replays the echo** (`queryFromScope(scope)`), not the switcher, so the file always matches the banner. An older server without the echo falls back to the query that produced the figures.
- **Who voided.** The server stores the principal's subject id (37-10). The block says "you" for the viewer's own account and uses the staff-directory name when the viewer is a Group admin, the only role that may read the directory, which is loaded at most once. Otherwise it says "a Group admin" (only Group admins can void) and never the raw id. This is the 31.1-13 allergy-note rule.
- **Card-paid** means `paymentStatus === "CAPTURED"` or a payment reference. Only then does the dialog add the no-refund sentence. The Refund action stays where it was.
- **The dialog keeps the ConfirmActionDialog shape but is its own component.** ConfirmActionDialog has no slot for a form field, and widening its props for one caller would change a shared component under five other call sites.
- **The dashboard home finance tile loads in its own effect,** so waiting for staff/me never holds up the other tiles. A body that is not a summary is treated as "no figures", never as NaN or a chart crash. The first GREEN run caught this through an existing test whose mock answered the scoped URL with a page body.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] `useOptionalShopSwitcherData()` in `shop-switcher-provider.tsx`** (not in the plan's file list)
- **Issue:** the finance page and OrderDetailPanel need staff/me (groupAdmin, userId) to apply the B4/B5 role rules. `useShopSwitcherData()` throws outside the provider, and several existing suites render these pages bare.
- **Fix:** a non-throwing variant that returns null outside the provider. Null decides nothing, and the server stays the boundary.
- **Committed in:** `81d4f105`

**2. [Rule 3 - Blocking] `types/api.ts`, `finance-scope-text.tsx`, `voided-badge.tsx`, `orders/[id]/page.tsx`** (not in the plan's file list)
- **Fix:**
  - `types/api.ts` gains the 37-10/37-11 response fields: `entryKind`, `orderId`, `reversesTransactionId`, `shopId`, `scope`, and the void triple on `Order`/`OrderDetail`.
  - The banner sentence is shared between the finance page and the home tile.
  - The Voided badge is shared between finance, the detail and the list.
  - The detail route keeps the void's 200 body as its order.
- **Committed in:** `81d4f105`, `d659795a`

**3. [Rule 1 - Bug] A non-summary body crashed the dashboard home** (found by the existing `page.test.tsx` #485 arm)
- **Fix:** `fetchFinanceSummary` returns `summary: null` unless the body has `totalRevenuePennies` and a `vatBreakdown` array.
- **Committed in:** `81d4f105`

**4. [Rule 1 - Instrument] Arm C first run did not apply**
- **Issue:** the Write path had stored U+2212 as the literal character, so the arm's pattern matched 0 times and its rc=0 run proved nothing.
- **Fix:** the result was discarded rather than reported. `dea57659` spelled the sign as `String.fromCharCode(0x2212)` (`MINUS_SIGN`), and the arm then went red.

**5. [Rule 3 - Blocking] Break-arm tooling**
- **Issue:** the machine's base-python guard refused a Python script file (the same as in 37-11).
- **Fix:** the arms ran through a small Node replace-exactly-once helper in the scratchpad, which refuses when the count is not 1.

---

**Total deviations:** 5 (2 Rule 1, 1 Rule 2, 2 Rule 3). **Impact:** #3 would have shipped a dashboard that crashes when the summary comes back malformed, and #4 would have reported a vacuous pass. No scope creep: every extra file serves B4/B5.

## Issues Encountered

- **Known limitation (WINDOWS #26).** Finance marks a SALE "Voided" only when its REVERSAL is on the same page of 20. The ledger DTO has no voided flag on the sale, and the reversal is newer, so on page boundaries a sale can appear without its badge. Its REVERSAL row is always labelled, and the totals are right either way.
- **No date-range picker** exists on the page. Every view and export uses the open range (`from`/`to` null), and the export reproduces whatever range the API echoes.
- **Live proof is owed to 37-15 (WINDOWS #25).** The compose runtime predates 37-10/37-11, so a browser run now would exercise old backend code and is not reported as proof.
- `docs/metrics.json` drift from the +40 jest blocks is Phase 37 deferred item 1, handled at the phase PR and not here.

## User Setup Required

None.

## Threat Flags

None beyond the plan's register:
- **T-37-33 (mitigated):** the banner and export read the echoed scope, with disagreement arm A.
- **T-37-34 (mitigated):** the UI gates on staff/me.groupAdmin, with role arm F; the server refuses anyway (37-10 403).
- **T-37-SC:** no package installed (0-byte diff).

The staff-directory read is the existing `GET /api/v1/staff`. It is made only when the viewer is already a Group admin, who reads it on the Staff page anyway.

## Next Phase Readiness

- 37-15: rebuild the frontend and core-java, then prove live:
  - the manager's banner and figures, and the export filename
  - the void, with its badge on the detail and in the list
  - a voided order's sale and reversal on /dashboard/finance
- RWO-027 and RWO-018 are listed here as this plan's contribution. Their REQUIREMENTS.md checkboxes stay open because 37-15 also declares them (`requirements.ready-ids` 0/2).

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-09*

## Self-Check: PASSED

All 8 key created files exist. Commits 62bedf6a, 81d4f105, dea57659, e2b329ed and d659795a are ancestors of HEAD (5 in `0462bf80..d659795a`). The must-have artifact `void-order-dialog.tsx` contains "Add a reason to void this order". `finance/page.tsx` reads `scope` from `fetchFinanceSummary`, the summary key link.
