/**
 * My Orders states, per order, the allergens the customer confirmed at checkout — Phase 31.1-18
 * (#785, D-08; D-07, D-06).
 *
 * The history list carries the acknowledged set and the channel from ORDER COLUMNS (31.1-09). It
 * never carries the recorded set: that needs the order lines, one fetch per row, and the server
 * measured that N+1 and refused it. So this page shows the acknowledged line and LINKS to the
 * tracking page for the full record — and must not quietly re-introduce the N+1 from the client.
 *
 * The same three absences as everywhere else, never conflated: VENDOR (the shop placed it, so
 * there was nothing to confirm), NOT RECORDED (a pre-acknowledgement order) and [] (they confirmed
 * a basket that declared none of the 14).
 */
import { fireEvent, render, screen, within } from "@testing-library/react"
import { OrdersClient } from "../orders-client"
import type { OrderSummary, OrdersLoad } from "@/lib/customer-orders"
import {
  MY_ORDERS_ACK_HEADING_COPY,
  MY_ORDERS_FULL_RECORD_LINK_COPY,
  RECORDED_ACK_NONE_COPY,
  RECORDED_ACK_NOT_RECORDED_COPY,
  VENDOR_PLACED_COPY,
} from "@/components/storefront/recorded-allergen-set"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => Promise.resolve({ profile: { email: "buyer@example.com" } }),
}))

function order(partial: Partial<OrderSummary> & Record<string, unknown>): OrderSummary {
  return {
    orderNumber: "ORD-00000000-20261006-AAAAAAAA",
    status: "COMPLETED",
    shopName: "Mama Ade's Kitchen",
    totalAmountPennies: 1250,
    itemCount: 1,
    unitCount: 1,
    createdAt: "2026-10-05T12:00:00Z",
    updatedAt: "2026-10-05T12:30:00Z",
    ...partial,
  } as OrderSummary
}

function loadOf(...orders: OrderSummary[]): OrdersLoad {
  return { state: "ok", orders, totalElements: orders.length }
}

const STOREFRONT = order({
  orderNumber: "ORD-1111-STOREFRONT",
  placedVia: "STOREFRONT",
  acknowledgedAllergenMask: 65,
  acknowledgedAllergens: ["Gluten", "Milk"],
  recordedAllergens: null,
})
const VENDOR = order({
  orderNumber: "ORD-2222-VENDOR",
  placedVia: "VENDOR",
  acknowledgedAllergenMask: null,
  acknowledgedAllergens: null,
})
const PRE_ACK = order({
  orderNumber: "ORD-3333-PREACK",
  placedVia: null,
  acknowledgedAllergenMask: null,
  acknowledgedAllergens: null,
})
const DECLARED_NONE = order({
  orderNumber: "ORD-4444-NONE",
  placedVia: "STOREFRONT",
  acknowledgedAllergenMask: 0,
  acknowledgedAllergens: [],
})

const rowFor = (orderNumber: string) => screen.getByTestId(`order-allergens-${orderNumber}`)

let fetchMock: jest.Mock
beforeEach(() => {
  fetchMock = jest.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ content: [] }) })
  global.fetch = fetchMock as unknown as typeof fetch
  sessionStorage.clear()
})

describe("My Orders: the allergens each order recorded at checkout (#785, D-08)", () => {
  it("states the confirmed set on the order's row", () => {
    render(<OrdersClient initial={loadOf(STOREFRONT)} email="buyer@example.com" />)
    expect(rowFor(STOREFRONT.orderNumber)).toHaveTextContent(`${MY_ORDERS_ACK_HEADING_COPY} Gluten, Milk`)
    expect(MY_ORDERS_ACK_HEADING_COPY).toBe("Allergens you confirmed:")
  })

  it("vendor-placed, not-recorded and declared-none are three different sentences, none of them 'no allergens'", () => {
    render(<OrdersClient initial={loadOf(VENDOR, PRE_ACK, DECLARED_NONE)} email="buyer@example.com" />)
    const vendor = rowFor(VENDOR.orderNumber)
    const preAck = rowFor(PRE_ACK.orderNumber)
    const none = rowFor(DECLARED_NONE.orderNumber)

    expect(vendor).toHaveTextContent(VENDOR_PLACED_COPY)
    expect(preAck).toHaveTextContent(RECORDED_ACK_NOT_RECORDED_COPY)
    expect(none).toHaveTextContent(RECORDED_ACK_NONE_COPY)
    expect(vendor).not.toHaveTextContent(RECORDED_ACK_NOT_RECORDED_COPY)
    expect(preAck).not.toHaveTextContent(VENDOR_PLACED_COPY)
    for (const row of [vendor, preAck]) {
      expect(row.textContent ?? "").not.toMatch(/none|no allergens/i)
    }
  })

  it("links each order to its tracking page for the full record, named with the order number", () => {
    render(<OrdersClient initial={loadOf(STOREFRONT, VENDOR)} email="buyer@example.com" />)
    const link = within(rowFor(STOREFRONT.orderNumber)).getByRole("link", {
      name: `${MY_ORDERS_FULL_RECORD_LINK_COPY} for order ${STOREFRONT.orderNumber}`,
    })
    expect(link).toHaveAttribute("href", `/track?order=${STOREFRONT.orderNumber}`)
    // Never a link inside the card's link: one anchor per destination, no nesting.
    expect(link.closest("a")?.parentElement?.closest("a")).toBeNull()
    // WR-09: the email travels out of band, never in the URL.
    expect(link.getAttribute("href")).not.toMatch(/email|buyer/)
    fireEvent.click(link)
    expect(sessionStorage.getItem("jtoye-track-email")).toBe("buyer@example.com")
  })

  it("renders N orders without one extra request per order (no client-side N+1)", async () => {
    const many = Array.from({ length: 6 }, (_, i) =>
      order({ orderNumber: `ORD-${i}-PAST`, placedVia: "STOREFRONT", acknowledgedAllergens: ["Milk"] })
    )
    render(<OrdersClient initial={loadOf(...many)} email="buyer@example.com" />)
    const rows = screen.getAllByTestId(/^order-allergens-/)
    expect(rows).toHaveLength(6)
    for (const row of rows) expect(row).toHaveTextContent(`${MY_ORDERS_ACK_HEADING_COPY} Milk`)
    // Let any effect-scheduled request fire before counting.
    await new Promise((r) => setTimeout(r, 50))
    expect(fetchMock).toHaveBeenCalledTimes(0)
  })
})

describe("My Orders dates are UK dates (goods P2-CHA-18)", () => {
  it("an order placed at 00:01 BST on 4 Oct is labelled 4 Oct", () => {
    // 23:01Z on 3 Oct is 00:01 BST on 4 Oct. Falsified by running under TZ=America/New_York with
    // the formatter's timeZone removed ("3 Oct").
    render(
      <OrdersClient
        initial={loadOf(order({ orderNumber: "ORD-CHA-18", createdAt: "2026-10-03T23:01:00Z" }))}
        email="buyer@example.com"
      />
    )
    expect(screen.getByText(/^4 Oct/)).toBeInTheDocument()
    expect(screen.queryByText(/^3 Oct/)).not.toBeInTheDocument()
  })
})
