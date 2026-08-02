---
phase: 23-vendor-scoped-access-responsive-dashboard-nav
plan: 18
subsystem: fullstack
tags: [pagination, shop-scoping, vendor-scoped-access, products, marketing, openapi, wr-04]

# Dependency graph
requires:
  - phase: 23-03
    provides: "Grant-scoped server-side read narrowing — the set this plan pages over was already correct; only WHERE the filter runs changed"
  - phase: 23-05
    provides: "The persisted shop-context switcher (getShopContext/subscribeShopContext) whose selection these screens must honour"
  - phase: 23-07
    provides: "useShopContext threaded into Products/Orders/Marketing/Kitchen — this plan fixes the two screens it wired client-side"
provides:
  - "Optional ?shopId= on GET /products, /products/search, /promotions and /announcements — server-side narrowing with a real per-shop totalElements"
  - "Three getXByShop service methods each opening with shopAccessService.require(shopId, ShopRole.STAFF), cloned from the already-correct OrderService.getOrdersByShop"
  - "ProductRepository.findByShopId(UUID, Pageable) — new; the paged sibling of the pre-existing non-paged finder"
  - "Products and marketing screens refetch on shop change and render the server's total instead of filtered.length"
affects: [phase-23-pr, dashboard-products, dashboard-marketing, openapi-snapshot]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Shop-scoped list endpoint = optional @RequestParam UUID shopId + ternary dispatch to a service method that opens with require(shopId, STAFF) — the OrderController/OrderService shape, now uniform across four endpoints"
    - "A shop-scope Jest assertion must assert the REQUEST (?shopId=), not the rendered rows: rendered-row assertions pass equally against the client-side .filter() being removed, so they cannot distinguish the two implementations"

key-files:
  created:
    - core-java/src/test/java/uk/jtoye/core/security/access/ShopScopedListGateTest.java
    - core-java/src/test/java/uk/jtoye/core/product/ProductControllerShopFilterTest.java
    - core-java/src/test/java/uk/jtoye/core/shop/MarketingControllerShopFilterTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/product/ProductController.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductRepository.java
    - core-java/src/main/java/uk/jtoye/core/product/ProductService.java
    - core-java/src/main/java/uk/jtoye/core/shop/PromotionController.java
    - core-java/src/main/java/uk/jtoye/core/shop/PromotionService.java
    - core-java/src/main/java/uk/jtoye/core/shop/AnnouncementController.java
    - core-java/src/main/java/uk/jtoye/core/shop/AnnouncementService.java
    - frontend/app/dashboard/products/page.tsx
    - frontend/app/dashboard/marketing/page.tsx
    - frontend/app/dashboard/__tests__/products-orders-shop-scope.test.tsx
    - frontend/app/dashboard/__tests__/marketing-kitchen-shop-scope.test.tsx
    - docs/api/openapi-snapshot.json

key-decisions:
  - "/products/search IS scoped here — left alone, the switcher would silently stop applying the moment a vendor typed two characters (the screen swaps to /search at length >= 2), trading a uniform defect for an inconsistent one. Additive: the frozen array return shape is untouched"
  - "Marketing's client-side statusFilter is deliberately NOT fixed — same defect class, separate defect; widening the change would blur what is being verified. Filed as #306 rather than silently carried"
  - "The single-shop finders deliberately exclude legacy shop_id IS NULL rows, matching exactly what the client filter did, so no visible behaviour changes beyond the defect. getAllProducts still includes them for the All-shops view"
  - "The pager resets to page 0 on shop change — the orders screen being copied does NOT do this, so switching while on page 3 could strand you on an out-of-range empty page. Deliberately not inherited"
  - "Not a security fix. The set was already grant-scoped server-side (23-03) and use-shop-context.ts:19-22 documents itself as a UX narrow, NOT a security boundary. What changed is where the filter runs"

patterns-established:
  - "Assert the request, not the render, when replacing a client-side filter with a server-side one — and update the mock to honour the new param, or a correct implementation looks broken"

requirements-completed: []

# Metrics
duration: unrecorded (executed 2026-07-26)
completed: 2026-07-26
summary-written: 2026-08-02
---

# Phase 23 Plan 18: WR-04 — server-side shop narrowing for products and marketing

> ## ⚠ This SUMMARY is RETROACTIVE, written 2026-08-02 for work that shipped 2026-07-26
>
> **The work was done and merged.** Issue **#280** is CLOSED; PR **#308** merged 2026-07-26T21:01:55Z
> as `d8b7c052`. What was missing was only this file.
>
> **Why that mattered enough to fix a week later:** `gsd-sdk` marks a plan complete **solely by the
> presence of a `SUMMARY.md`**. Without this file an unscoped `/gsd:execute-phase 23` would have
> **re-executed already-merged work** against a tree where it is already applied. The gap was found
> during the 2026-08-01 state review and recorded in `HANDOFF.md` §2.2.
>
> **Why it went missing:** the executor wrote its evidence back into `23-18-PLAN.md` — which carries
> full both-directions Evidence and Suites sections — instead of into a SUMMARY. The evidence was
> never lost; only the completion marker was.
>
> **Provenance of the numbers below.** Suite counts and both-directions arms are quoted **as recorded
> at execution time (2026-07-26)** in `23-18-PLAN.md`. They were **not** re-run on 2026-08-02. What
> *was* re-verified on 2026-08-02 is that every artifact exists and carries the claimed behaviour —
> see *Re-verification* below.

## The defect

Products and marketing narrowed to the selected shop **client-side, over a single already-paginated
page**. Three user-visible consequences:

1. **Wrong counts** — the header read `filtered.length`, i.e. *"matches on this page"*, not the
   shop's total.
2. **False empty state** — a shop whose rows began on page 2 rendered *"No products in this shop"*.
3. **Unreachable rows** — `<Pagination>` was driven by the *unfiltered* server totals.

Plus one the original record missed: the fetch effects **never listed `contextShopId` as a
dependency**, so switching shop did not even refetch.

Adjudicated a **milestone blocker** by the v2.3 backlog review (#307) — the only open v2.3 deferral
that **misreports a vendor's own data back to them**.

## Accomplishments

- Optional `?shopId=` on **four** endpoints — `GET /products`, `/products/search`, `/promotions`,
  `/announcements` — each dispatching to a service method that opens with
  `shopAccessService.require(shopId, ShopRole.STAFF)`.
- `ProductRepository.findByShopId(UUID, Pageable)` added. **`23-REVIEW.md:591` claimed
  `findByShopIdIn` already existed on `ProductRepository` — it does not**; that finder is on the
  Order/Promotion/Announcement repos. Verified rather than trusted.
- `ProductService.getProductsByShop` preserves `.map(productMapper::toDto).map(this::resolveAssetFirst)`
  — dropping `resolveAssetFirst` would have silently broken product imagery (Phase 24 CoW assets).
- Both screens refetch on shop change, reset to page 0, and render the server's `totalElements`.
- OpenAPI snapshot regenerated: **4 `shopId` param blocks added, 0 paths removed**.

## Verification evidence — as recorded 2026-07-26

### A2 — the authorization gate (`ShopScopedListGateTest`)

**FAIL direction.** All four `require(shopId, ShopRole.STAFF)` calls removed by `sed` (verified
`gate calls remaining: 0 0 0`): **7 tests completed, 7 failed**.

**PASS direction.** Gates restored: **25 tests executed, 0 failures** — counted from
`build-local/test-results/test/*.xml`, *not* from "BUILD SUCCESSFUL", which is identical whether or
not anything ran.

### A6 — server-side narrow (Jest)

**FAIL direction.** `shopScope` neutralised at all 4 fetcher sites: **3 failed, 13 passed** — exactly
the three request-shape assertions.

**PASS direction.** Restored: **16 passed, 16 total**.

> **Why the request assertion is the load-bearing one.** The pre-existing tests asserted only the
> **rendered rows**, which passed equally well against the client-side `.filter()` being removed — so
> they could never distinguish the two implementations. The mocks were also updated to honour
> `?shopId=`; a mock that ignored it would return every shop's rows and make a correct implementation
> look broken.

### Suites

| Gate | Result |
|---|---|
| `:core-java:test` (with `cleanTest`) | **111 files / 792 tests / 0 fail / 0 err**, counted from `build-local` |
| `:core-java:integrationTest` | **98 files / 392 tests / 1 skip / 0 fail** in **39m56s** — elapsed recorded deliberately, since a 9-second "pass" is a path-gated short-circuit, not a run. Matches the Phase 26 baseline exactly |
| `OpenApiSnapshotTest` | **1/0/0** — asserts byte-equality against live `/v3/api-docs`, so it would have failed had the committed snapshot not matched the four new params |
| `npx jest` (full) | **62 suites / 411 tests / 0 fail** |
| `npm run build` (the tsc gate — jest does NOT type-check) | exit 0 |
| `npx eslint` (changed files) | 1 warning at `products/page.tsx:488`, past the last diff hunk — pre-existing |
| `scripts/docs-freshness.sh` | exit 0 at **1759** (was 1736), `CLAUDE.md:15` + `AGENTS.md:15` prose reconciled in the same commit |

## Re-verification on 2026-08-02, before writing this file

Claims were checked against the live tree rather than copied:

| claim | result |
|---|---|
| `ShopScopedListGateTest` exists, nested Products / Promotions / Announcements | ✅ present, all three `@Nested` classes |
| `ProductControllerShopFilterTest` | ✅ present |
| `MarketingControllerShopFilterTest` | ✅ present — with/without/malformed-400 for **both** promotions and announcements (6 `isBadRequest`/`not-a-uuid` hits) |
| `ProductRepository.findByShopId(UUID, Pageable)` | ✅ `:66`, alongside the non-paged `:54` |
| `?shopId=` on all four endpoints | ✅ `ProductController:63` (list) and `:97` (search), plus Promotion/Announcement controllers |
| Jest specs assert the **request** shape | ✅ 8 and 13 `shopId=` occurrences; a control token returned **0**, proving the count can miss |

**One correction made during that re-verification, recorded because it is the repo's own documented
trap.** A `find` for `PromotionControllerShopFilterTest` and `AnnouncementControllerShopFilterTest`
— the two names the plan's T6 used — returned **MISSING**, and was very nearly written up as a
coverage gap. Reading the shipping commit showed both had landed **consolidated into one
`MarketingControllerShopFilterTest`**. *An empty search is evidence about the pattern, not about the
code.* Coverage is complete; only the class naming deviated.

## Deviations from plan

1. **T6 class naming** — the plan named `PromotionControllerShopFilterTest` +
   `AnnouncementControllerShopFilterTest`; one `MarketingControllerShopFilterTest` shipped covering
   both, mirroring the fact that "Marketing" is a two-tab UI over two domains. Coverage matches the
   plan exactly (with-shopId / without-shopId / malformed-400 per domain).
2. **No SUMMARY at execution time** — the subject of this document.

## Known limits of this evidence

**A1 (page 2 reachable), A4 (true total) and A5 (no false empty state) are proven at the controller
and screen level only.** They were **not** proven against a live multi-page dataset in a browser — no
seeded tenant has a shop with >20 products. That is a real limit of the evidence, recorded rather
than papered over. Cheapest closure: a live check on the next stack run.

## Follow-ups deliberately not absorbed

- **#306** — marketing's client-side `statusFilter` / `announcementStatusFilter` over one page. Same
  defect class, separate defect. Filed, not dropped; the code comment above those filters points at it.
- `/products/search` has no real pagination (`totalPages` is hardcoded to 1). Scoping it by shop is
  additive here; paging it properly is a separate change, recorded inside #306.

## Requirements

**None newly completed.** This closes the **WR-04 deferral** recorded under VSA-04 in
`REQUIREMENTS.md`, and materially strengthens VSA-03 ("all shop-scoped screens operate on the selected
shop") — but both VSA-03 and VSA-04 were already `Complete` before this plan ran, so the traceability
table is unchanged. Recording it as completing a requirement would overstate what happened.

## Note on `docs/metrics.json`

This plan reconciled the count to **1759**. It reads **1917** today — that is later work
(Phases 24–27 and the August fixes), **not** drift introduced here. Do not "correct" it toward 1759.

## Self-Check: PASSED
