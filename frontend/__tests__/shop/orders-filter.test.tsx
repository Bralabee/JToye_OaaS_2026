/**
 * Unit tests for the pure filter + pagination derivation used by
 * frontend/app/shop/orders/page.tsx (STFR-05).
 *
 * Intentionally pure-logic only — no React rendering. The orders page
 * requires RequireCustomerAuth + publicApiClient + NextAuth session, which
 * is out of scope for a gap-closure unit spec. See 10-03-PLAN §Task 2.
 */

import {
  deriveOrdersView,
  ORDERS_PAGE_SIZE,
  type OrderSummary,
} from "@/app/shop/orders/page"

function mkOrder(i: number, status: string, createdAt: string): OrderSummary {
  return {
    orderNumber: `ORD-${String(i).padStart(4, "0")}`,
    status,
    shopName: "Test Shop",
    totalAmountPennies: 1000 + i,
    itemCount: 1,
    createdAt,
    updatedAt: createdAt,
  }
}

describe("deriveOrdersView", () => {
  it("returns empty paged with totalPages=1 for empty input", () => {
    const result = deriveOrdersView([], {
      statusFilter: "ALL",
      dateFrom: "",
      page: 1,
      pageSize: ORDERS_PAGE_SIZE,
    })
    expect(result.filtered).toEqual([])
    expect(result.paged).toEqual([])
    expect(result.totalPages).toBe(1)
  })

  it("filters by statusFilter=CONFIRMED, keeping only CONFIRMED orders", () => {
    const orders: OrderSummary[] = [
      mkOrder(1, "PENDING", "2026-04-01T10:00:00Z"),
      mkOrder(2, "CONFIRMED", "2026-04-02T10:00:00Z"),
      mkOrder(3, "CONFIRMED", "2026-04-03T10:00:00Z"),
      mkOrder(4, "COMPLETED", "2026-04-04T10:00:00Z"),
    ]
    const result = deriveOrdersView(orders, {
      statusFilter: "CONFIRMED",
      dateFrom: "",
      page: 1,
      pageSize: ORDERS_PAGE_SIZE,
    })
    expect(result.filtered).toHaveLength(2)
    expect(result.filtered.map(o => o.status)).toEqual(["CONFIRMED", "CONFIRMED"])
    expect(result.paged).toHaveLength(2)
    expect(result.totalPages).toBe(1)
  })

  it("filters by dateFrom and excludes orders older than the cutoff", () => {
    const orders: OrderSummary[] = [
      mkOrder(1, "COMPLETED", "2026-03-20T10:00:00Z"),
      mkOrder(2, "COMPLETED", "2026-04-01T09:00:00Z"),
      mkOrder(3, "COMPLETED", "2026-04-05T12:00:00Z"),
      mkOrder(4, "COMPLETED", "2026-04-10T08:00:00Z"),
    ]
    const result = deriveOrdersView(orders, {
      statusFilter: "ALL",
      dateFrom: "2026-04-01",
      page: 1,
      pageSize: ORDERS_PAGE_SIZE,
    })
    expect(result.filtered.map(o => o.orderNumber)).toEqual([
      "ORD-0002",
      "ORD-0003",
      "ORD-0004",
    ])
  })

  it("paginates 25 orders into 3 pages of 10/10/5", () => {
    const orders: OrderSummary[] = Array.from({ length: 25 }, (_, i) =>
      mkOrder(i + 1, "PENDING", `2026-04-${String((i % 28) + 1).padStart(2, "0")}T10:00:00Z`)
    )

    const p1 = deriveOrdersView(orders, {
      statusFilter: "ALL",
      dateFrom: "",
      page: 1,
      pageSize: ORDERS_PAGE_SIZE,
    })
    expect(p1.totalPages).toBe(3)
    expect(p1.paged).toHaveLength(10)
    expect(p1.paged[0].orderNumber).toBe("ORD-0001")

    const p2 = deriveOrdersView(orders, {
      statusFilter: "ALL",
      dateFrom: "",
      page: 2,
      pageSize: ORDERS_PAGE_SIZE,
    })
    expect(p2.paged).toHaveLength(10)
    expect(p2.paged[0].orderNumber).toBe("ORD-0011")

    const p3 = deriveOrdersView(orders, {
      statusFilter: "ALL",
      dateFrom: "",
      page: 3,
      pageSize: ORDERS_PAGE_SIZE,
    })
    expect(p3.paged).toHaveLength(5)
    expect(p3.paged[0].orderNumber).toBe("ORD-0021")
    expect(p3.paged[4].orderNumber).toBe("ORD-0025")
  })

  it("returns an empty slice when an overflow page is requested", () => {
    const orders: OrderSummary[] = Array.from({ length: 5 }, (_, i) =>
      mkOrder(i + 1, "PENDING", "2026-04-01T10:00:00Z")
    )
    const result = deriveOrdersView(orders, {
      statusFilter: "ALL",
      dateFrom: "",
      page: 5,
      pageSize: ORDERS_PAGE_SIZE,
    })
    expect(result.totalPages).toBe(1)
    expect(result.paged).toEqual([])
  })
})
