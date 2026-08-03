/**
 * pickTrackedOrder — the selection rule behind the #458 auto-populated
 * tracking view for a signed-in customer.
 *
 * Deliberately pure and exported so the rule can be falsified without a render.
 * The security-relevant property is the FIRST test: the `?order=` deep link is
 * matched against the caller's OWN list, never fetched by number, so a link to
 * somebody else's order resolves to null rather than to that order. (The server
 * refuses it too — `findByOrderNumberAndCustomerEmail` — but the client must not
 * be the only thing standing between the two, nor the thing that undoes it.)
 */
import { pickTrackedOrder, ACTIVE_STATUSES, type OrderStatus } from "@/app/track/page"

function order(n: string, status: string, createdAt: string): OrderStatus {
  return {
    orderNumber: n,
    status,
    shopName: "Mama Ade's Kitchen",
    totalAmountPennies: 1050,
    itemCount: 1,
    createdAt,
    updatedAt: createdAt,
  }
}

const MINE = [
  order("ORD-A-OLD-DONE", "COMPLETED", "2026-08-01T10:00:00Z"),
  order("ORD-A-NEW-ACTIVE", "PREPARING", "2026-08-03T09:00:00Z"),
  order("ORD-A-MID-ACTIVE", "PENDING", "2026-08-02T09:00:00Z"),
]

describe("pickTrackedOrder", () => {
  it("returns null for an order number that is not in the caller's own list", () => {
    expect(pickTrackedOrder(MINE, "ORD-SOMEONE-ELSE")).toBeNull()
  })

  it("honours a ?order= deep link when it IS one of the caller's orders", () => {
    expect(pickTrackedOrder(MINE, "ORD-A-OLD-DONE")?.orderNumber).toBe("ORD-A-OLD-DONE")
  })

  it("with no deep link, opens on the most recent ACTIVE order", () => {
    expect(pickTrackedOrder(MINE)?.orderNumber).toBe("ORD-A-NEW-ACTIVE")
  })

  it("prefers an active order even when a completed one is newer", () => {
    const withNewerDone = [...MINE, order("ORD-A-NEWEST-DONE", "COMPLETED", "2026-08-04T09:00:00Z")]
    expect(pickTrackedOrder(withNewerDone)?.orderNumber).toBe("ORD-A-NEW-ACTIVE")
  })

  it("falls back to the most recent order overall when nothing is in flight", () => {
    const allDone = [
      order("ORD-D1", "COMPLETED", "2026-08-01T10:00:00Z"),
      order("ORD-D2", "CANCELLED", "2026-08-02T10:00:00Z"),
    ]
    expect(pickTrackedOrder(allDone)?.orderNumber).toBe("ORD-D2")
  })

  it("returns null for an empty list rather than throwing", () => {
    expect(pickTrackedOrder([])).toBeNull()
    expect(pickTrackedOrder([], "ORD-ANY")).toBeNull()
  })

  it("treats exactly the in-flight statuses as active", () => {
    expect(ACTIVE_STATUSES).toEqual(["PENDING", "CONFIRMED", "PREPARING", "READY"])
    expect(ACTIVE_STATUSES).not.toContain("COMPLETED")
    expect(ACTIVE_STATUSES).not.toContain("CANCELLED")
  })
})
