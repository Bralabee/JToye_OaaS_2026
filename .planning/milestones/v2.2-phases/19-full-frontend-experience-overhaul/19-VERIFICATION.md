---
phase: 19-full-frontend-experience-overhaul
verified: 2026-07-11T19:57:51Z
status: human_needed
score: 6/6 must-haves verified (code-level); 1 blocking human-verify checkpoint not yet resolved
overrides_applied: 0
human_verification:
  - test: "Open the dashboard on a real 390px device/emulator as a logged-in vendor (real Keycloak session) and visually confirm the bottom tab bar (4 tabs + More drawer), desktop sidebar hidden, and page titles readable/unwrapped across all 11 routes."
    expected: "Bottom tab bar renders cleanly, titles do not wrap one-word-per-line, /dashboard/onboarding is visually unregressed — automated Playwright evidence is green, but the 'comparator-grade' aesthetic verdict requires human eyes (orchestrator cannot enter vendor credentials)."
    why_human: "Visual/aesthetic quality judgment behind a login wall — 19-HUMAN-UAT.md explicitly marks this 'USER EYES PENDING' (automated evidence only)."
  - test: "Open /dashboard/kitchen and an order-detail page as a logged-in vendor and visually confirm real product names render (no 'Unknown Product'), badges don't clip, and elapsed time reads as capped text."
    expected: "Kitchen cards and order-detail show real seeded product names; status badge doesn't overlap the (truncated) order number; elapsed time shows 'Xm/Xh/Xd ago' not raw minutes."
    why_human: "Visual/aesthetic quality judgment behind a login wall — 19-HUMAN-UAT.md explicitly marks this 'USER EYES PENDING' (automated evidence only)."
  - test: "Give the explicit 'approved' resume signal for 19-09 Plan Task 3 (checkpoint:human-verify, gate=blocking) after reviewing the whole app end-to-end (390px + desktop) per the 6 ROADMAP success criteria in 19-09-PLAN.md's how-to-verify list."
    expected: "Human types 'approved' (or lists issues to fix) — this is the phase's own designed sign-off gate and has not yet been given; STATE.md records it as 'NOT self-approved' and 'remaining'."
    why_human: "The plan itself designed this as a blocking human checkpoint (type=\"checkpoint:human-verify\" gate=\"blocking\") specifically because comparator-grade UX quality is a human judgment call, not a scriptable assertion."
---

# Phase 19: Full-Frontend Experience Overhaul Verification Report

**Phase Goal:** Every visitor — customer, prospective vendor, or operator — lands on a coherent product: a real front door routes them to their surface, every page is reachable through navigation, every flow is complete and comparator-grade (Deliveroo/Just Eat for storefront, Square/Toast for dashboard), on mobile first.
**Verified:** 2026-07-11T19:57:51Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (ROADMAP Success Criteria → UIX-01..06)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `/` renders a real public landing page (no blind redirect); shared header/footer connect `/`, `/for-operators`, `/business-model-guide`, `/track`, `/shop`; zero orphan routes, enforced by a link-graph test (UIX-01) | ✓ VERIFIED | `app/page.tsx` has 0 `redirect("/dashboard")`, 0 `"use client"`, contains `href="/shop"` + `href="/for-operators"` + `PublicShell`. `components/public/{public-shell,public-header,public-footer}.tsx` exist and are wired. `__tests__/link-graph.test.ts` exists and passes live (`4/4`, ran directly). `href="/track"` app-wide count = 4 (≥3 required). |
| 2 | All 11 dashboard routes usable at 390px — sidebar collapses to a bottom tab bar (4 tabs + More drawer); desktop (`>=md`) unchanged; Playwright mobile-viewport spec passes (UIX-02) | ✓ VERIFIED (code) / pending human aesthetic sign-off | `sidebar.tsx` exports `navigation` once and wraps the root in `hidden md:flex`. `mobile-tab-bar.tsx` imports the same `navigation` array (no fork), fixed bottom bar with `md:hidden`. `e2e/dashboard-mobile.spec.ts` exists, covers all 11 routes, 0 `networkidle` on route navigations (only a `.catch()`-guarded `networkidle` wait before the login-page SSO click, not on dashboard routes). `dashboard-shell.test.tsx` passes live (ran directly). Session evidence: live Playwright 62 pass/2 skip/2 residual on rebuilt stack. Dashboard visual verdict flagged "USER EYES PENDING" in `19-HUMAN-UAT.md`. |
| 3 | Kitchen display + order detail show real product names on live orders — `OrderItem` snapshot populated at creation; "Unknown Product" never renders for an existing product (UIX-03) | ✓ VERIFIED (code) / pending human aesthetic sign-off | `PublicStorefrontService.createGuestOrder` calls `item.setProductName(product.getTitle())`. `V45__order_fulfilment.sql` backfills historical `'Unknown Product'` rows. `OrderFulfilmentAuditIntegrationTest` (`@Tag("testcontainers")`) proves a real audited write with no Envers drift. Kitchen card badge-clip fixed (`min-w-0 truncate text-lg font-semibold` + `flex-shrink-0`); `elapsedText()` caps at "just now"/"Xm ago"/"Xh ago"/"Xd ago". `OrderDetailPanel.tsx` renders `productName`. All targeted jest suites pass live. 9 pre-existing legacy `order_items` rows remain "Unknown Product" (unresolvable legacy refs) — explicitly documented as a residual, not a regression, in `deferred-items.md`. |
| 4 | Checkout collects a UK delivery address (persisted via V45) and shows Subtotal + Delivery + VAT + Total BEFORE payment; Playwright checkout e2e updated (UIX-04) | ✓ VERIFIED | `checkout/page.tsx`: `"Final total confirmed"` footnote count = 0; fulfilment toggle + conditional UK address block; `previewDeliveryFeePennies()` mirrors `PublicStorefrontService`'s server-authoritative waiver line-for-line (COLLECTION or above-threshold ⇒ £0); order payload sends flat `fulfilmentType`/`addressLine1`/`addressCity`/`addressPostcode` matching `GuestOrderRequest.java` field names exactly. `checkout.test.tsx` (9 tests) passes live. Human UAT recorded a real order placed end-to-end (ORD-00000000-20260711-7FD1E4DE) with server breakdown matching the preview (1p VAT display rounding noted as cosmetic, non-blocking). |
| 5 | Each shop renders its own menu — products assigned `shop_id`; `ProductRepository` `IS NULL` fallback removed deliberately (UIX-05) | ✓ VERIFIED | `ProductRepository.findAvailableByShopOrderedByCategory` query is strictly `p.shopId = :shopId ORDER BY ...` — 0 occurrences of `shopId IS NULL`. `DemoDataSeeder.java` is `@Profile("dev")`, quarantines non-curated products into a hidden archive shop (fixed a duplicate-line-item regression discovered during 19-09 live triage). `ProductRepositoryScopingIntegrationTest` (Testcontainers) asserts per-shop disjointness + NULL-shop-id absence + no duplicate line items. Human UAT confirmed two shops (Mama Ade's Kitchen / Peckham Jollof Co.) render fully disjoint menus with real brand logos. |
| 6 | All 15 audit backlog items closed/deferred; 921 logical invocations stay green (grow, not regress); palette stays orange/emerald/slate + blue-600, no purple, no sub-12px (UIX-06) | ✓ VERIFIED | `grep -rn "purple-" app components` = 0; `grep -rn "text-\[10px\]" app components` = 0 (and the discipline test's stricter sub-12px range gate = 0); `grep -rlE "#[0-9a-fA-F]{3,8}" components/marketing/*.tsx` = 0. `__tests__/palette-discipline.test.ts` exists and passes live, locking all four gates in CI. `docs/metrics.json`: `total_logical_invocations` = 988 (≥921, no regression), `schema_version` = 45. `docs-freshness.sh` exits 0. REQUIREMENTS.md documents all 15 backlog items (14 closed to a plan + #14 explicit LEAVE-AS-IS + #15 fallback-not-photography annotation + OQ3 deferred edge case). Minor pre-existing, out-of-scope residual: `text-violet-*` (15 occurrences, AI-suggestion badge in `products/page.tsx`) predates Phase 19, was not part of the original 13-hit "purple" audit finding (#10), and is outside this phase's grep-gate contract — see Anti-Patterns below. |

**Score:** 6/6 truths verified at the code level. **Status is `human_needed`, not `passed`,** because the phase's own closure plan (19-09 Task 3) designed a blocking `checkpoint:human-verify` gate for exactly this class of judgment (comparator-grade visual/UX quality), and `19-HUMAN-UAT.md` + `STATE.md` both explicitly record that gate as not yet given its "approved" resume signal — 2 dashboard-side criteria (UIX-02, UIX-03) are marked "USER EYES PENDING" because they sit behind a real Keycloak login the orchestrator will not enter credentials for.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `frontend/app/page.tsx` | Landing page replacing redirect | ✓ VERIFIED | 151 lines, no redirect, no `"use client"`, both door links present |
| `frontend/components/public/public-shell.tsx` | Shared header+footer wrapper | ✓ VERIFIED | Plain server component, no client directive, wraps `PublicHeader`+`PublicFooter` |
| `frontend/__tests__/link-graph.test.ts` | Orphan-route guard | ✓ VERIFIED | Exists, passes live (4/4) |
| `frontend/components/ui/sheet.tsx` | Vendored shadcn sheet | ✓ VERIFIED | Exists, built on `@radix-ui/react-dialog`; `git status --porcelain package.json` clean |
| `frontend/components/dashboard/mobile-tab-bar.tsx` | 4-tab bottom bar + More sheet | ✓ VERIFIED | Imports shared `navigation`, no fork, `md:hidden` present |
| `frontend/e2e/dashboard-mobile.spec.ts` | Playwright mobile proof, 11 routes | ✓ VERIFIED | Exists, all 11 routes covered, 0 `networkidle` on route assertions |
| `core-java/.../V45__order_fulfilment.sql` | orders+orders_aud fulfilment/address + backfill | ✓ VERIFIED | 5 nullable `orders_aud` columns, 1 CHECK on base table only, productName backfill present, no V44 file created |
| `core-java/.../FulfilmentType.java` | DELIVERY\|COLLECTION enum | ✓ VERIFIED | Present as specified |
| `core-java/.../OrderFulfilmentAuditIntegrationTest.java` | Audited-write no-drift proof | ✓ VERIFIED | `@Tag("testcontainers")` present |
| `core-java/.../dev/DemoDataSeeder.java` | Dev-only realistic seed, shop_id assigned | ✓ VERIFIED | `@Profile("dev")` = 1, no "test shop"/lorem literal, quarantine logic for non-curated rows (19-09 hardening) |
| `core-java/.../product/ProductRepositoryScopingIntegrationTest.java` | Per-shop isolation proof | ✓ VERIFIED | `@Tag("testcontainers")`, 3 test methods (disjointness, NULL-absence, no-duplicate) |
| `frontend/app/shop/[slug]/checkout/page.tsx` | Fulfilment toggle + address + fee-before-pay | ✓ VERIFIED | `fulfilmentType` in payload, `/public/shops/` fetch present, `Subtotal` breakdown present, footnote removed |
| `frontend/app/shop/[slug]/checkout/__tests__/checkout.test.tsx` | Fulfilment/postcode/fee-preview coverage | ✓ VERIFIED | Passes live |
| `frontend/__tests__/palette-discipline.test.ts` | Grep-gate lock (purple/text/hex/track) | ✓ VERIFIED | Passes live; all 4 gates green |
| `.planning/REQUIREMENTS.md` | UIX-01..06 registration + traceability | ✓ VERIFIED | All 6 registered with descriptions, closing plans, and Traceability table rows; 15-item backlog table present |
| `docs/metrics.json` | Reconciled counts, schema_version 45 | ✓ VERIFIED | `schema_version: 45`, `total_logical_invocations: 988`; `docs-freshness.sh` exits 0 |
| `docs/CHANGELOG.md` | Phase 19 [Unreleased] entry | ✓ VERIFIED | Present at `docs/CHANGELOG.md` (plan frontmatter said `CHANGELOG.md` at root — file lives in the project's actual convention location; content is complete and accurate) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `PublicStorefrontService.java` | `OrderItem.productName` | `item.setProductName(product.getTitle())` | ✓ WIRED | Confirmed present, root cause of "Unknown Product" fixed |
| `V45__order_fulfilment.sql` | `orders_aud` | `ALTER TABLE orders_aud ADD COLUMN` ×5, nullable, no CHECK | ✓ WIRED | Confirmed, matches V38-lesson idiom |
| `mobile-tab-bar.tsx` | `sidebar.tsx` navigation | shared exported array import | ✓ WIRED | `import { navigation } from "@/components/dashboard/sidebar"`, 0 re-declared arrays |
| `dashboard-shell.tsx` | `MobileTabBar` | `md:hidden` + `pb-20` clearance | ✓ WIRED | Confirmed present |
| `checkout/page.tsx` payload | `GuestOrderRequest.java` | flat `fulfilmentType`/`addressLine1`/`addressCity`/`addressPostcode` | ✓ WIRED | Field names match exactly between client payload and server DTO |
| `checkout/page.tsx` `previewDeliveryFeePennies` | `PublicStorefrontService` delivery-fee block | mirrored waiver logic (COLLECTION/threshold ⇒ £0) | ✓ WIRED | Logic verified line-for-line equivalent in both files |
| `app/track/page.tsx` | `GET /public/orders/{orderNumber}?email=` | `publicApiClient` guest lookup, no `RequireCustomerAuth` | ✓ WIRED | `RequireCustomerAuth` count = 0 in file |
| `__tests__/palette-discipline.test.ts` | `app/` + `components/` | static grep gates | ✓ WIRED | All 4 gates pass live |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| Kitchen card / OrderDetailPanel | `item.productName` | `Order.getItems()` ← `OrderItem.productName` snapshotted at guest-order creation from `product.getTitle()`, backfilled by V45 for historical rows | Yes — real DB-persisted product titles, not a static/empty fallback | ✓ FLOWING |
| Checkout fee breakdown | `subtotal`/`deliveryFee`/`vat`/`total` | `previewDeliveryFeePennies()` computed from a real `GET /public/shops/{slug}` fetch (`deliveryFeePennies`, `freeDeliveryThresholdPennies`); server recomputes authoritatively on order creation | Yes — client preview reads live shop data; server total is the DB write of record | ✓ FLOWING |
| Storefront menu (`/shop/[slug]`) | shop's product list | `ProductRepository.findAvailableByShopOrderedByCategory` strictly `p.shopId = :shopId`, backed by `DemoDataSeeder` real per-shop rows | Yes — DB query, no static/empty fallback; per-shop isolation proven by Testcontainers | ✓ FLOWING |
| Mobile dashboard nav | `navigation` array | Single exported array in `sidebar.tsx`, imported by `mobile-tab-bar.tsx` | Yes — same source as the desktop sidebar, no hardcoded duplicate/empty array | ✓ FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Link-graph + palette-discipline + landing render | `npx jest __tests__/link-graph.test.ts __tests__/palette-discipline.test.ts app/__tests__/landing.test.tsx` | 3 suites / 14 tests passed | ✓ PASS |
| Checkout + dashboard shell + kitchen + order-detail + track render | `npx jest .../checkout.test.tsx .../dashboard-shell.test.tsx .../kitchen/__tests__/page.test.tsx .../OrderDetailPanel.test.tsx app/__tests__/track.test.tsx` | 5 suites / 40 tests passed | ✓ PASS |
| `docs-freshness.sh` (metrics vs source counts) | `bash scripts/docs-freshness.sh` | "docs-freshness OK: metrics match source (total logical invocations: 988)" exit 0 | ✓ PASS |
| Purple / text-[10px] / marketing-hex / track-link grep gates | direct `grep -rn`/`grep -rlE` over `app`/`components` | purple=0, text-[10px]=0, marketing-hex=0, track-links=4 | ✓ PASS |
| ProductRepository NULL-fallback removal | `grep -n "shopId IS NULL\|shopId = :shopId ORDER BY"` | 0 NULL-fallback matches, 1 strictly-scoped match | ✓ PASS |
| Playwright/Gradle full suite | Not re-run this session (session-level evidence already established: gradle test+integrationTest BUILD SUCCESSFUL ×2, jest 177/177, npm build green, live Playwright 62 pass/2 skip/2 residual against rebuilt Docker stack) | Accepted as evidence, cross-referenced against SUMMARY/STATE.md claims and this session's independent spot-checks (all consistent) | ✓ PASS (accepted evidence) |

### Probe Execution

No `scripts/*/tests/probe-*.sh` convention exists in this repository and none is declared in any 19-0X-PLAN.md or 19-0X-SUMMARY.md. Step 7c: SKIPPED (no probe-based verification convention used by this project; the project's verification mechanism is Gradle/Jest/Playwright test suites, which are covered under Behavioral Spot-Checks above).

### Requirements Coverage

| Requirement | Source Plan(s) | Description | Status | Evidence |
|--------------|----------------|--------------|--------|----------|
| UIX-01 | 19-03, 19-05 | Landing page + connected IA, zero orphans | ✓ SATISFIED | Landing page, public shell, link-graph guard, marketing re-skin, guest `/track`, all confirmed in codebase |
| UIX-02 | 19-04 | All 11 dashboard routes usable at 390px | ✓ SATISFIED (code) / pending human sign-off | Shared-nav mobile bar + desktop-only sidebar confirmed; Playwright spec covers all 11 routes; visual verdict pending |
| UIX-03 | 19-01, 19-07 | Real product names, no "Unknown Product" | ✓ SATISFIED (code) / pending human sign-off | Backend snapshot fix + backfill + badge/elapsed fixes confirmed; visual verdict pending |
| UIX-04 | 19-01, 19-06 | Address + fee-before-payment | ✓ SATISFIED | V45 schema, checkout UI, server/client fee parity, real order placed in UAT |
| UIX-05 | 19-02 | Per-shop menus, no bleed | ✓ SATISFIED | Query scoping, Testcontainers isolation proof, seeder quarantine fix, UAT-confirmed disjoint menus |
| UIX-06 | 19-07, 19-08, 19-09 | 15-item backlog closure, no regression, palette locked | ✓ SATISFIED | All 15 items accounted for in REQUIREMENTS.md; grep gates + discipline test green; metrics grew 921→988, no regression |

No orphaned requirements — REQUIREMENTS.md Traceability table's Coverage summary states "Unmapped: 0" and all 6 UIX IDs from `.planning/REQUIREMENTS.md` and the ROADMAP Phase 19 section match exactly.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `frontend/app/dashboard/products/page.tsx` | 67,69,75,647,650-665 | `text-violet-*` (15 occurrences, AI-suggestion badge) — outside the `orange/emerald/slate/blue-600/amber` locked palette | ℹ️ INFO | Pre-existing (predates Phase 19; not among the original 18-UI-REVIEW.md's 13-hit "purple" finding #10, which used the Tailwind `purple-` prefix specifically, not `violet-`). Not touched by any Phase-19 plan; 19-08-SUMMARY.md explicitly documents this as "out of scope... a candidate for a follow-up palette pass." Does not fail any phase must-have (the `palette-discipline.test.ts` gate is scoped to `purple-`, matching the plan's own exhaustive map) but is a residual departure from the ROADMAP's literal "no purple" / locked-palette language if a viewer treats violet as part of the same forbidden hue family. Not blocking — recommend a follow-up ticket. |
| `frontend/app/dashboard/orders/page.tsx` | 591 | `// (deferred deprecation — frontend cleanup TBD).` | ℹ️ INFO | Pre-existing from Phase 17-04 (commit `c8082ad`, 2026-07-06), predates Phase 19 by 5 days. Only the purple-related line in this file was touched by Phase 19 (commit `cde877c`); this TBD comment is untouched and references documented prior-phase context (17-CONTEXT), not a new debt marker introduced by this phase. Not a Phase-19 blocker per the debt-marker gate (applies to markers introduced by this phase's own changes). |

No TBD/FIXME/XXX/HACK/PLACEHOLDER markers were introduced by any of the 62 files touched across Phase 19's 9 plans. No stub returns (`return null`/`return {}`/`return []`/empty handlers) were found in the newly created shell/checkout/kitchen/seeder artifacts inspected.

### Human Verification Required

### 1. Dashboard mobile visual/aesthetic sign-off (UIX-02)

**Test:** Log in as a vendor on a real 390px device/emulator, open all 4 bottom tabs + the More drawer across the 11 dashboard routes, and judge whether it reads as comparator-grade (Square/Toast quality) — not just functionally present.
**Expected:** Bottom tab bar renders cleanly, titles are readable (not one-word-wrapped), sidebar is gone, `/dashboard/onboarding` looks unregressed.
**Why human:** This sits behind a real Keycloak login the orchestrator will not enter credentials for; automated Playwright evidence (62 pass/2 skip/2 residual) proves functional correctness but not the subjective "comparator-grade" aesthetic bar the phase goal explicitly names. `19-HUMAN-UAT.md` records this as "USER EYES PENDING."

### 2. Dashboard kitchen/order-detail visual sign-off (UIX-03)

**Test:** Log in as a vendor, open `/dashboard/kitchen` and an order-detail page, and visually confirm real product names, clean badges, and capped elapsed time read well in the live UI (not just pass a DOM assertion).
**Expected:** No "Unknown Product" on real/seeded orders; badge doesn't clip; elapsed time reads naturally.
**Why human:** Same login-wall constraint as above; `19-HUMAN-UAT.md` records this as "USER EYES PENDING."

### 3. Phase closure sign-off — the plan's own blocking human-verify gate

**Test:** Complete Task 3 of `19-09-PLAN.md` (`type="checkpoint:human-verify" gate="blocking"`) — walk the 6 ROADMAP success criteria end-to-end on the rebuilt stack (390px + desktop) and give the explicit resume signal ("approved", or a list of issues).
**Expected:** Human confirms the app is functional AND comparator-grade, per the plan's own acceptance criteria.
**Why human:** The plan itself designed this checkpoint as blocking specifically because "comparator-grade" is a judgment call the plan's authors deliberately did not attempt to fully automate. `STATE.md` and `19-HUMAN-UAT.md` both currently record this gate as NOT yet given its approval signal (7/7 items the orchestrator itself could test passed, but 2 are explicitly deferred to the user, and the overall gate resume-signal has not been recorded).

### Gaps Summary

No code-level gaps were found — every one of the 6 UIX requirements (mapped 1:1 to the 6 ROADMAP success criteria) has concrete, wired, tested evidence in the codebase: real artifacts exist, are substantive (not stubs), are wired end-to-end (frontend payload field names match backend DTO fields exactly; client fee-preview logic mirrors server logic line-for-line; shared navigation array eliminates fork risk), and data flows from real DB queries rather than static/empty fallbacks. All 15 audit backlog items are accounted for in REQUIREMENTS.md, the test count grew from 921 to 988 logical invocations with `docs-freshness.sh` green, and every grep-gate/palette-discipline lock passes live.

The reason this phase is `human_needed` rather than `passed` is structural, not a code deficiency: Phase 19's own closure plan (19-09) deliberately built a blocking `checkpoint:human-verify` gate for the two dashboard-side success criteria that sit behind a real login wall, and that gate's resume signal ("approved") has not yet been recorded by the human. This verifier is honoring that same design — a phase whose own plan requires human sign-off cannot be marked `passed` until that sign-off is given, per the gates.md Escalation Gate pattern and the verification workflow's explicit rule that `passed` is only valid when the human-verification section is empty.

---

*Verified: 2026-07-11T19:57:51Z*
*Verifier: Claude (gsd-verifier)*
