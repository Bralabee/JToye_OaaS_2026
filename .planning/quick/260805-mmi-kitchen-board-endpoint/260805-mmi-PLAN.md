---
quick_id: 260805-mmi
slug: kitchen-board-endpoint
date: 2026-08-05
issue: 564
branch: feat/564-kitchen-board-endpoint
status: in-progress
---

# Quick task: the KDS board asks one question and pays N requests for it

Closes #564. Follows #563, which made the board **tolerant** of the resulting rate-limit
refusals without reducing them.

## Why a batch endpoint and not the cheaper options

#564 listed three. Only one satisfies its own acceptance — *"a number of requests that
does not grow linearly with N"*:

| option | verdict |
|---|---|
| bounded concurrency | **moves the cliff**, does not remove it. Same total request count, just spread out — a 40-ticket board still costs 41 |
| honour `Retry-After` + retry | lengthens the window in which the board is stale; complements a fix, is not one |
| **batch endpoint** | the only one that changes the *shape* of the cost |

## What the board actually asks

One question: **"all active orders for this shop, with their line items."** Today that is
answered with `1 + N` HTTP requests, and the `online` handler fires the whole thing again
on recovery — measured 19 + 19 inside ~400 ms on an 18-ticket board, against a tenant
bucket of `capacity(120).refillIntervally(100, 1 min)`.

## The second defect on the same path, stated rather than smuggled

`fetchActiveKitchenOrders` pages the shop's **entire order history** (bounded at
`MAX_KITCHEN_ORDER_PAGES = 20`) and filters for kitchen statuses **client-side**. Brixton
Village Grill: 43 lifetime orders for 18 active. A shop past ~2,000 lifetime orders could
exhaust the page bound *before reaching its live tickets* — the board would then be
missing tickets and would say so (`truncated`), which is honest but useless.

Filtering by status **server-side** fixes that too. It is a real second improvement and is
recorded here so it is not read as a side effect.

## Tasks

- **T1 — `OrderRepository`.** `Page<Order> findByShopIdAndStatusIn(...)`, plus a
  `left join fetch` query for items by id.
  ⚠ **Do NOT put `@EntityGraph` on the paged query.** Hibernate cannot paginate a fetched
  `@OneToMany` in SQL, so it falls back to **in-memory pagination** (HHH000104): it reads
  every matching row and slices in Java. That is a silent, unbounded read — the opposite
  of this change. Two queries instead: page the ids, then fetch items for that page.
- **T2 — `OrderService.getKitchenBoard(shopId, pageable)`.**
  `shopAccessService.require(shopId, ShopRole.STAFF)` — **identical to `getOrdersByShop`**;
  a new read must not invent a new rule. Refunds are today an N+1 of their own
  (`refundService.findByOrderId` per order): batch with one `findByOrderIdIn` and group in
  memory. **Not** left empty — the KDS does not render refunds, but a DTO field that is
  empty because nobody filled it is regression by omission.
- **T3 — `OrderController`.** `GET /api/v1/orders/kitchen?shopId={uuid}` →
  `Page<OrderDetailDto>`, kitchen statuses only, `createdAt desc`. Read-only, so no
  Idempotency-Key; existing OpenAPI annotation style and RFC 7807 errors.
- **T4 — frontend.** `fetchKitchenBoard(shopId)` replaces both calls, preserving the #485
  paging-honest contract (`last`/`totalPages`/short-page exits, page bound, `truncated`
  surfaced so an incomplete board still says so).
- **T5 — account for the displaced good** (below).
- **T6 — verification** (below).

## The displaced good, accounted for — not dropped

#563 shipped partial-failure tolerance (`Promise.allSettled`, `failedIds`, `unrenderable`)
because a `1 + N` burst could be **partly** refused. With one request a refusal is
**total**, so the state that machinery exists to handle becomes unreachable and the code
becomes dead.

Retiring it is correct — *removing* the burst is strictly better than *tolerating* it —
but it is recorded in the PR, the CHANGELOG and the SUMMARY, naming what went and why the
property can no longer occur. Deleting a just-shipped fix silently is how a regression
gets re-learned.

**The invariant that survives and keeps a test:** a read the board cannot complete still
raises the banner; a successful one clears it.

## Verification

- **#564's own acceptance:** request count not linear in N, at N ≥ 18, read from a trace
  against the live stack; `X-RateLimit-Remaining` must not reach 0 during one refresh.
- **A new E2E test that counts requests** — strictly stronger than the tests it replaces,
  because it encodes the acceptance itself. Must be shown to FAIL on the current build.
- **BOLA:** this is a new read taking a caller-supplied `shopId`. A shop the caller has no
  grant on must be denied, and a foreign tenant's shopId must yield nothing. **Run both** —
  RLS covers the tenant half only; the shop half is application-layer.
- Every new check shown to fail first; `:core-java:integrationTest` on Testcontainers;
  counts read from `build-local` (`core-java/build/` is a stale 2025-12-27 artifact
  reporting false failures).
- Regenerate `docs/api/openapi-snapshot.json` and `docs/metrics.json --write`.
- Full E2E suite, 26 repo gates, 6 k8s gates.
