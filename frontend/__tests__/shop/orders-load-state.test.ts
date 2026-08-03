/**
 * Issue #467 — a failed request must never be presentable as "no orders".
 *
 * These tests guard the type-level fix rather than a rendering detail. The bug
 * was that every failure path did `setOrders([])`, so a 502 and a genuine empty
 * history became the same state and the page printed "No orders found for this
 * email." over a customer who had 26 orders.
 *
 * `toOrdersLoad` is the single place that decision is now made — shared by the
 * server component's fetch and the client island's refetch, so the two cannot
 * drift apart. The load-bearing property is asserted in BOTH directions: an
 * error never yields an `orders` array, and a real empty page still does.
 */

import {
  isCapped,
  toOrdersLoad,
  type OrdersLoad,
} from "@/lib/customer-orders"

function pageBody(rows: unknown[], totalElements?: number) {
  return {
    content: rows,
    totalElements: totalElements ?? rows.length,
    totalPages: 1,
    number: 0,
    size: 100,
  }
}

const order = {
  orderNumber: "ORD-0001",
  status: "PENDING",
  shopName: "Mama Ade's Kitchen",
  totalAmountPennies: 1699,
  itemCount: 2,
  createdAt: "2026-08-01T09:54:00Z",
  updatedAt: "2026-08-01T09:54:00Z",
}

/** The empty state is legitimate ONLY through this shape. */
function rendersEmptyState(load: OrdersLoad): boolean {
  return load.state === "ok" && load.orders.length === 0
}

describe("toOrdersLoad — request failure is not an empty list (#467)", () => {
  it("maps the 502 that broke /shop/orders to an error, not an empty list", () => {
    // The exact response the compose stack produced: the route's own catch.
    const load = toOrdersLoad(502, { error: "upstream_unavailable" })
    expect(load.state).toBe("error")
    expect(rendersEmptyState(load)).toBe(false)
    expect(load).not.toHaveProperty("orders")
  })

  it.each([500, 502, 503, 504, 400, 404, 418])(
    "maps HTTP %s to an error state",
    (status) => {
      const load = toOrdersLoad(status, {})
      expect(load.state).toBe("error")
      expect(rendersEmptyState(load)).toBe(false)
    }
  )

  it("maps 401 and 403 to `unauthenticated` so the customer is asked to sign in, not to retry", () => {
    expect(toOrdersLoad(401, {})).toEqual({ state: "error", reason: "unauthenticated" })
    expect(toOrdersLoad(403, {})).toEqual({ state: "error", reason: "unauthenticated" })
  })

  it("maps other failures to `upstream` so the customer is offered a retry", () => {
    expect(toOrdersLoad(502, {})).toEqual({ state: "error", reason: "upstream" })
  })

  it("still shows the empty state for a genuine 200 with zero orders", () => {
    // The other direction. A fix that always errors would be just as wrong.
    const load = toOrdersLoad(200, pageBody([]))
    expect(load.state).toBe("ok")
    expect(rendersEmptyState(load)).toBe(true)
  })

  it("returns the orders for a 200 with rows", () => {
    const load = toOrdersLoad(200, pageBody([order]))
    expect(load).toEqual({ state: "ok", orders: [order], totalElements: 1 })
    expect(rendersEmptyState(load)).toBe(false)
  })

  it("treats a 200 whose body is not a page as an error, not as zero orders", () => {
    // A contract break is a failure. Reporting it as "no orders" would
    // reintroduce the bug through a different door.
    for (const body of [null, undefined, "", "nope", 42, {}, { content: null }, { content: "x" }]) {
      const load = toOrdersLoad(200, body)
      expect(load.state).toBe("error")
      expect(rendersEmptyState(load)).toBe(false)
    }
  })

  it("falls back to the row count when totalElements is missing", () => {
    const load = toOrdersLoad(200, { content: [order] })
    expect(load).toEqual({ state: "ok", orders: [order], totalElements: 1 })
  })
})

describe("isCapped — the silent page cap is now stated (#463)", () => {
  it("is true when the customer has more orders than were returned", () => {
    expect(isCapped({ state: "ok", orders: [order], totalElements: 137 })).toBe(true)
  })

  it("is false when the page holds everything", () => {
    expect(isCapped({ state: "ok", orders: [order], totalElements: 1 })).toBe(false)
    expect(isCapped({ state: "ok", orders: [], totalElements: 0 })).toBe(false)
  })

  it("is false for an error, which knows nothing about how many orders exist", () => {
    expect(isCapped({ state: "error", reason: "upstream" })).toBe(false)
  })
})
