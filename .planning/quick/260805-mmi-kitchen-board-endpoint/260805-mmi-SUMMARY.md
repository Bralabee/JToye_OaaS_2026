---
quick_id: 260805-mmi
slug: kitchen-board-endpoint
date: 2026-08-05
issue: 564
branch: feat/564-kitchen-board-endpoint
status: complete
---

# Summary — the board asked one question and paid N requests for it

Closes #564. Follows #563, which made the board **tolerant** of the resulting rate-limit
refusals without reducing them.

## The acceptance, measured

`GET /api/v1/orders/kitchen?shopId=…` returns active orders **with** their line items.
Measured against the live stack on the same 18-ticket board (`Brixton Village Grill`),
same test, same offline→online cycle:

| | before (#561's trace) | after |
|---|---|---|
| `/api/v1/orders*` requests | **38** (19 + 19) | **2** |
| `/detail` requests | 36 | **0** |
| statuses | 28 × 200 + **10 × 429** | 2 × 200 |
| lowest `X-RateLimit-Remaining` | **0** | **115** (of 120) |

Both of #564's acceptance criteria hold: the count does not scale with N, and the bucket
is never exhausted by one refresh.

## The second defect on the same path, fixed and stated

`fetchActiveKitchenOrders` paged the shop's **entire order history** (bounded at 20
pages) and filtered for kitchen statuses **in the browser** — 43 lifetime rows for 18
live tickets on the dev tenant, and a shop past ~2,000 lifetime orders could exhaust the
bound *before reaching its live tickets*. The status filter is now server-side, so the
result is bounded by what is on the board rather than by how long the shop has traded.

## Two N+1s removed rather than relocated

- **items** — a `left join fetch` by id, run **after** the page is decided.
  **Not** `@EntityGraph` on the paged query: a fetched `@OneToMany` cannot be paginated
  in SQL, so Hibernate drops the limit, reads every matching row and paginates in memory
  (HHH000104). That would have been an unbounded read wearing a paged response — this
  very defect reintroduced one layer down, and invisible from the outside.
- **refunds** — one `findByOrderIdIn` for the page. `OrderDetailDto` carries refunds and
  the KDS does not render them, but a field left empty because nobody filled it is a lie
  the next consumer inherits.

## The displaced good, accounted for

#563's partial-failure tolerance (`Promise.allSettled`, `failedIds`, the page's
`unrenderable` branch, four unit tests, one page test) is **retired**. It existed because
a `1 + N` burst could be *partly* refused; with one request a refusal is **total**, so
that state is unreachable and a test for it would assert against reality.

Removing the burst is strictly better than tolerating it — but it is recorded in the test
file, the page, the plan, the commits and the changelog rather than vanishing, because a
just-shipped fix disappearing without a trace is how the same defect gets re-learned.

**What survives, tested in both suites:** a read the board cannot complete still raises
the banner, and a successful one clears it — asserted in *both* directions in one test,
since "the banner appears" is satisfied by a board that always warns.

Also retired: the `incremental` flag and the `ordersMap` ref behind it, which existed so
the 60 s poll could skip re-reading detail. The drift they allowed — a detail edit that
did not change status went unseen until "something of consequence" happened — is gone
rather than optimised.

## Three things I got wrong, all caught by running something

1. **The cross-tenant test was written to the wrong expectation.** I expected a foreign
   tenant's `shopId` to yield an **empty page** via RLS. It **404s** — `require()` runs
   FC-1's `requireShopInCallerTenant` first, so the caller cannot even tell the shop
   exists. Stronger than predicted, and the endpoint inherits it *by reusing the existing
   check instead of reasoning out a new one*. A test written to the guess would have
   failed a correct system.
2. **My first acceptance test asserted "exactly one request" and failed at 2.** The page
   runs its load effect twice on mount — independent of ticket count, and not what #564
   is about. A constant would have made the test a tripwire for unrelated render
   behaviour; it now asserts the count is the **same** for one ticket and for eight.
3. **`-Djtoye.openapi.mode=update` silently did not reach the test JVM.** The repo has a
   dedicated `:core-java:updateOpenApiSnapshot` task; the `-D` form produced a plain test
   failure and an unchanged snapshot, which reads like a broken contract rather than a
   wrong command.

## Verification

- **Break arms, both proven, both restores hash-verified against HEAD:**
  removing the shop gate fails **both** kitchen tests (the denial *and* the cross-tenant
  404, since both flow through `require()`); widening `KITCHEN_STATUSES` to include DRAFT
  fails the active-only test alone. Closing clean arm 14/14.
- **The permitted side is asserted, not just the denial** — a gate that rejects
  everything is indistinguishable from a correct one when only the deny direction is
  checked.
- **Non-vacuity on the cross-tenant zero**: the foreign board is read from *inside* its
  own tenant and proven non-empty, so the isolation result is isolation rather than an
  empty seed. (This repo has a recorded trap where an unpinned query under RLS returns 0
  rows on a full table.)
- `:core-java:integrationTest` ShopAccessEnforcementIntegrationTest **14/14** on real
  Postgres; `OpenApiSnapshotTest` green in check mode after regeneration.
- jest **91 suites / 789 tests**; `npm run build` exit 0.
- `docs/metrics.json` regenerated: java 1433 → 1435, jest 747 → 745. **The total is
  unchanged at 2391 by coincidence** — which is precisely why the docs quote per-suite
  numbers; a stable total hid real movement in both directions.
