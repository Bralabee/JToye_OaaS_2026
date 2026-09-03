/**
 * Shared model for the customer's order history ("My Orders").
 *
 * ISOMORPHIC — imported by the server component that renders the page, by the
 * client island that refreshes it, and by the unit tests. It deliberately holds
 * no React and no `next/*` import so all three can use exactly the same rules.
 *
 * Issue #467: the reason this module exists at all is that "the request failed"
 * and "you have no orders" used to be the SAME state. The page kept
 * `orders: OrderSummary[]`, every failure path did `setOrders([])`, and the
 * empty-state branch was `orders.length === 0`. A 502 therefore rendered as
 * "No orders found for this email." — a confident wrong answer, indistinguishable
 * from the truth for the customer, for a screenshot, and for any test that only
 * asserts the page renders.
 *
 * The fix is a type, not a check: {@link OrdersLoad} is a discriminated union, so
 * the empty state is only reachable through `state: "ok"`. A failure cannot be
 * spelled as an empty list, because a failure has no list to be empty.
 */
import type { OrderStatus } from "@/types/api"

export interface OrderSummary {
  orderNumber: string
  status: string
  shopName: string
  totalAmountPennies: number
  itemCount: number
  /**
   * COR-4 (V66): UNITS on the order — what the customer counted in the basket. `itemCount` is
   * LINES and is what this surface used to render under the word "items", so a 6-Zobo order read
   * "1 item" here and "6 items" on the basket minutes earlier.
   *
   * Optional AND nullable, and the two absences mean the same thing: NOT RECORDED. Absent = an
   * older backend; null = a row written before V66. Neither may be coalesced to 0 or replaced by
   * `itemCount` — the count is simply not rendered when it is not known.
   */
  unitCount?: number | null
  createdAt: string
  updatedAt: string
}

/**
 * Why the load failed. Kept coarse on purpose — the customer needs to know
 * whether to retry or to sign in, and nothing more specific is actionable.
 */
export type OrdersErrorReason = "upstream" | "unauthenticated"

/**
 * The ONLY way to describe a load. There is no third state and no `null`
 * orders array: `orders` exists if and only if the request succeeded.
 */
export type OrdersLoad =
  | { state: "ok"; orders: OrderSummary[]; totalElements: number }
  | { state: "error"; reason: OrdersErrorReason }

/**
 * The page size requested from the API.
 *
 * Issue #463 flagged this as an unpaginated fetch. It is kept at 100, with two
 * changes that answer the actual complaint:
 *
 *  1. It is no longer on the customer's critical path. The first render is
 *     server-side, so this request costs the customer no client-side wait — it
 *     is a ~15 ms call inside the compose network, not a post-hydration round
 *     trip the spinner has to cover.
 *  2. The cap is now STATED rather than silent. 100 is the server's maximum page
 *     size (a larger `?size=` is capped with no signal), so asking for more is
 *     meaningless; when a customer actually has more than this, the UI says so
 *     via {@link isCapped} instead of presenting a truncated list as complete.
 *
 * The window exists because the status/date filters and the 10-per-page
 * pagination below are CLIENT-side — the core endpoint sorts and pages but has
 * no filter parameters, so filtering server-side would need a core-java change.
 * Fetching one page of 10 would silently narrow every filter to those 10.
 */
export const ORDERS_FETCH_SIZE = 100

export const ORDERS_PAGE_SIZE = 10

/**
 * Every server status, plus the ALL sentinel (INT-8).
 *
 * DRAFT and REFUNDED were missing, so a refunded order could not be filtered
 * for at all — it was not merely mislabelled, it was unfindable. The type-level
 * assertion below makes the next added status a COMPILE error here, in the same
 * spirit as `STATUS_CONFIG`'s `Record<OrderStatus, …>` on the badge map.
 */
export const ORDER_STATUS_OPTIONS = [
  "ALL",
  "DRAFT",
  "PENDING",
  "CONFIRMED",
  "PREPARING",
  "READY",
  "COMPLETED",
  "CANCELLED",
  "REFUNDED",
] as const

export type OrderStatusFilter = typeof ORDER_STATUS_OPTIONS[number]

/**
 * Compile-time completeness: `never` unless every `OrderStatus` is offered.
 * Deleting a status from the list above makes this line fail `tsc`, which is
 * what `npm run build` runs — jest cannot see it (it does not type-check).
 */
const _everyStatusIsFilterable: Exclude<OrderStatus, OrderStatusFilter> extends never
  ? true
  : never = true
void _everyStatusIsFilterable

/**
 * Statuses an order can never leave.
 *
 * REFUNDED was absent, so a refunded order was neither active nor terminal:
 * the card kept its pulsing "live" dot and "Track" CTA, and the 15-second
 * poller on the My-Orders page polled it forever. It is terminal on the server
 * too — `OrderStateMachineConfig` declares `.end(OrderStatus.REFUNDED)`.
 */
const TERMINAL_STATUSES: OrderStatus[] = ["COMPLETED", "CANCELLED", "REFUNDED"]

export function isActiveOrder(order: OrderSummary): boolean {
  return !TERMINAL_STATUSES.includes(order.status as OrderStatus)
}

/** True when the customer has more orders than this page is holding. */
export function isCapped(load: OrdersLoad): boolean {
  return load.state === "ok" && load.totalElements > load.orders.length
}

/**
 * Map an HTTP response into the load model. The SINGLE decision point for
 * "did this work", shared by the server fetch and the client refetch so the two
 * can never disagree about what a 502 means.
 *
 * Anything that is not a 2xx is an error. 401 is called out separately because
 * the answer for the customer is different: sign in again, not retry.
 */
export function toOrdersLoad(httpStatus: number, body: unknown): OrdersLoad {
  if (httpStatus === 401 || httpStatus === 403) {
    return { state: "error", reason: "unauthenticated" }
  }
  if (httpStatus < 200 || httpStatus >= 300) {
    return { state: "error", reason: "upstream" }
  }

  // A 200 whose body is not the expected Spring Page is NOT an empty order
  // list — it is a contract break, and reporting it as "no orders" would
  // reintroduce exactly the bug this module exists to prevent.
  if (!body || typeof body !== "object") {
    return { state: "error", reason: "upstream" }
  }
  const page = body as { content?: unknown; totalElements?: unknown }
  if (!Array.isArray(page.content)) {
    return { state: "error", reason: "upstream" }
  }

  const orders = page.content as OrderSummary[]
  const total =
    typeof page.totalElements === "number" ? page.totalElements : orders.length
  return { state: "ok", orders, totalElements: total }
}

/**
 * Pure filter + pagination derivation for the customer orders page.
 * Extracted so it can be unit-tested without rendering the React component.
 *
 * - statusFilter === "ALL" disables the status filter.
 * - dateFrom is an ISO yyyy-mm-dd string (from <input type="date">). Empty
 *   string disables the date filter. Orders with createdAt earlier than
 *   the start of the selected day are excluded.
 * - pageSize must be > 0; callers should use ORDERS_PAGE_SIZE.
 * - totalPages is clamped to at least 1 so the UI always has a label.
 * - paged returns the slice for the requested page. An overflow page
 *   (page > totalPages) returns an empty slice — the UI effect resets
 *   `page` to 1 when the filters change.
 */
export function deriveOrdersView(
  orders: OrderSummary[],
  opts: {
    statusFilter: OrderStatusFilter | string
    dateFrom: string
    page: number
    pageSize: number
  }
): { filtered: OrderSummary[]; paged: OrderSummary[]; totalPages: number } {
  const fromTs = opts.dateFrom ? new Date(opts.dateFrom).getTime() : null
  const filtered = orders.filter((o) => {
    if (opts.statusFilter !== "ALL" && o.status !== opts.statusFilter) return false
    if (fromTs !== null && !Number.isNaN(fromTs)) {
      const created = new Date(o.createdAt).getTime()
      if (Number.isNaN(created) || created < fromTs) return false
    }
    return true
  })
  const pageSize = opts.pageSize > 0 ? opts.pageSize : 1
  const start = Math.max(0, (opts.page - 1) * pageSize)
  return {
    filtered,
    paged: filtered.slice(start, start + pageSize),
    totalPages: Math.max(1, Math.ceil(filtered.length / pageSize)),
  }
}
