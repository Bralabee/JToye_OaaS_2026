/**
 * INT-8 — a REFUNDED order on the customer's own surfaces.
 *
 * The defect, from code: `STATUS_CONFIG` was typed `Record<string, …>` and held
 * six of the server's eight statuses, so a REFUNDED order fell through
 * `|| STATUS_CONFIG.PENDING` and told the customer their refunded order was
 * "Received". `TERMINAL_STATUSES` held only COMPLETED and CANCELLED, so the
 * same order counted as IN FLIGHT: pulsing live dot, "Track" CTA, amber active
 * border, and a 15-second poll that would never stop. The vendor's own map two
 * directories away renders "Refunded" correctly — because it is typed
 * `Record<OrderStatus, …>` and therefore HAD to supply the entry.
 *
 * NOT REACHABLE ON THIS RUNTIME, stated so the evidence tier is not overclaimed:
 * `RefundService` refuses an order with a null paymentReference and no order on
 * this stack has one (#461), so REFUNDED cannot be produced live. These are
 * code-path tests; there is no browser arm to point at, and saying otherwise
 * would be the exact overclaim this council exists to stop.
 */
import { render, screen } from "@testing-library/react"
import { OrdersClient } from "@/app/shop/orders/orders-client"
import { isActiveOrder, ORDER_STATUS_OPTIONS, type OrderSummary } from "@/lib/customer-orders"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: jest.fn(() => Promise.resolve(null)),
}))

function order(status: string): OrderSummary {
  return {
    orderNumber: `ORD-${status}`,
    status,
    shopName: "Mama Ade's Kitchen",
    totalAmountPennies: 650,
    itemCount: 1,
    createdAt: "2026-09-01T12:00:00Z",
    updatedAt: "2026-09-01T12:30:00Z",
  }
}

function renderOrders(...statuses: string[]) {
  return render(
    <OrdersClient
      initial={{ state: "ok", orders: statuses.map(order), totalElements: statuses.length }}
      email="customer@example.com"
    />
  )
}

/**
 * STATUS BADGES ONLY. The filter <select> renders the SAME human labels, so an
 * unscoped getByText finds two nodes for every status once the map is complete
 * — and a "not present" assertion would silently be about the dropdown.
 */
function badges(label: string): HTMLElement[] {
  return screen.queryAllByText(label).filter((el) => el.tagName !== "OPTION")
}

describe("INT-8 — the customer status map covers every server status", () => {
  it("labels a REFUNDED order 'Refunded', not 'Received'", () => {
    renderOrders("REFUNDED")

    expect(badges("Refunded")).toHaveLength(1)
    expect(badges("Received")).toHaveLength(0)
  })

  it("labels a DRAFT order 'Draft'", () => {
    renderOrders("DRAFT")

    expect(badges("Draft")).toHaveLength(1)
    expect(badges("Received")).toHaveLength(0)
  })

  it("still labels the six statuses that already worked", () => {
    renderOrders("PENDING", "CONFIRMED", "PREPARING", "READY", "COMPLETED", "CANCELLED")

    for (const label of ["Received", "Confirmed", "Preparing", "Ready", "Completed", "Cancelled"]) {
      expect(badges(label)).toHaveLength(1)
    }
  })

  it("offers every status in the filter, so a refunded order can be found", () => {
    renderOrders("REFUNDED")

    const select = screen.getByTestId("orders-status-filter")
    const values = Array.from(select.querySelectorAll("option")).map((o) => o.getAttribute("value"))
    expect(values).toContain("REFUNDED")
    expect(values).toContain("DRAFT")
    // The option renders its human label, not the raw enum.
    expect(select.textContent).toContain("Refunded")
  })

  it("keeps ORDER_STATUS_OPTIONS and the server enum the same set", () => {
    expect([...ORDER_STATUS_OPTIONS]).toEqual([
      "ALL",
      "DRAFT",
      "PENDING",
      "CONFIRMED",
      "PREPARING",
      "READY",
      "COMPLETED",
      "CANCELLED",
      "REFUNDED",
    ])
  })
})

describe("INT-8 — REFUNDED is terminal", () => {
  it("is not an active order", () => {
    expect(isActiveOrder(order("REFUNDED"))).toBe(false)
    expect(isActiveOrder(order("CANCELLED"))).toBe(false)
    expect(isActiveOrder(order("COMPLETED"))).toBe(false)
    // Control: the function still says YES to something genuinely in flight,
    // so a `false` above is about REFUNDED and not about a broken helper.
    expect(isActiveOrder(order("PREPARING"))).toBe(true)
  })

  it("shows 'View' rather than the live 'Track' affordance", () => {
    renderOrders("REFUNDED")

    expect(screen.getByText("View")).toBeInTheDocument()
    expect(screen.queryByText("Track")).not.toBeInTheDocument()
  })

  it("stops the 15s poller — and the same instrument sees a PENDING order polling", () => {
    jest.useFakeTimers()
    const fetchSpy = jest.fn(() =>
      Promise.resolve({ status: 200, json: () => Promise.resolve({ content: [], totalElements: 0 }) })
    )
    // @ts-expect-error — minimal fetch stand-in; only call counting matters here.
    global.fetch = fetchSpy

    try {
      const refunded = renderOrders("REFUNDED")
      jest.advanceTimersByTime(60_000)
      expect(fetchSpy).not.toHaveBeenCalled()
      refunded.unmount()

      // CONTROL ARM. Without it, "0 calls" could mean the poller never runs in
      // jsdom at all, which would make the assertion above worthless.
      renderOrders("PREPARING")
      jest.advanceTimersByTime(60_000)
      expect(fetchSpy.mock.calls.length).toBeGreaterThan(0)
    } finally {
      jest.useRealTimers()
      // @ts-expect-error — remove the stand-in again.
      delete global.fetch
    }
  })
})
