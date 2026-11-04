/**
 * #845 and #846 on My Orders (persona close-out 2026-11-04): a past order can be ordered again, and a
 * COMPLETED one can be reviewed.
 *
 *  - "Order again" is a link to the reorder page of the shop the order came from (the signed-in history now
 *    carries `shopSlug`), on the three PAST statuses; never on an order still in flight, never without a
 *    shop to go to (a shop that is no longer published).
 *  - "Rate your order" is the review disclosure, on COMPLETED orders only, built with the shop and the order
 *    number: the email is not an input to it.
 *
 * Both are SIBLINGS of the order's card link, never inside it (an anchor in an anchor is invalid), like the
 * allergen row beside them.
 */
import { fireEvent, render, screen, within } from "@testing-library/react"
import { useSearchParams } from "next/navigation"
import { OrdersClient } from "../orders-client"
import type { OrderSummary, OrdersLoad } from "@/lib/customer-orders"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => Promise.resolve({ profile: { sub: "u1", email: "buyer@example.com" }, expiresAt: 9999999999 }),
  customerLogin: jest.fn(),
  customerLogout: jest.fn(),
}))
jest.mock("@/lib/public-api-client", () => ({ __esModule: true, default: { get: jest.fn() } }))

function order(partial: Partial<OrderSummary>): OrderSummary {
  return {
    orderNumber: "ORD-AAAA",
    status: "COMPLETED",
    shopName: "Mama Ade's Kitchen",
    shopSlug: "mama-ades-kitchen",
    totalAmountPennies: 1250,
    itemCount: 1,
    unitCount: 1,
    placedVia: "STOREFRONT",
    acknowledgedAllergenMask: 1,
    acknowledgedAllergens: ["Gluten"],
    createdAt: "2026-11-01T12:00:00Z",
    updatedAt: "2026-11-01T12:05:00Z",
    ...partial,
  }
}
const loadOf = (...orders: OrderSummary[]): OrdersLoad => ({ state: "ok", orders, totalElements: orders.length })

beforeEach(() => {
  ;(useSearchParams as jest.Mock).mockReturnValue({ get: () => null })
  global.fetch = jest.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ content: [], totalElements: 0 }) }) as unknown as typeof fetch
  localStorage.clear()
  sessionStorage.clear()
})

describe("Order again", () => {
  it.each(["COMPLETED", "CANCELLED", "REFUNDED"])("a %s order links to its shop's reorder page, named for the order", (status) => {
    render(<OrdersClient initial={loadOf(order({ status, orderNumber: "ORD-AAAA" }))} email="buyer@example.com" />)
    const link = screen.getByRole("link", { name: /order again/i })
    expect(link).toHaveAttribute("href", "/shop/mama-ades-kitchen/reorder/ORD-AAAA")
    // Distinguishable when there are several orders: the visible words are the same, the name says which.
    expect(link).toHaveAccessibleName(/mama ade's kitchen/i)
  })

  it.each(["PENDING", "CONFIRMED", "PREPARING", "READY", "DRAFT"])("a %s order has no Order again (it is not a past order)", (status) => {
    render(<OrdersClient initial={loadOf(order({ status }))} email="buyer@example.com" />)
    // Non-vacuity: the card rendered.
    expect(screen.getByText("Mama Ade's Kitchen")).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /order again/i })).toBeNull()
  })

  it("an order whose shop is not published (no shopSlug) has no Order again and no review form", () => {
    render(<OrdersClient initial={loadOf(order({ shopSlug: null }))} email="buyer@example.com" />)
    expect(screen.getByText("Mama Ade's Kitchen")).toBeInTheDocument()
    expect(screen.queryByRole("link", { name: /order again/i })).toBeNull()
    expect(screen.queryByRole("button", { name: /rate your order/i })).toBeNull()
  })

  it("is not inside the card link (no anchor in an anchor)", () => {
    render(<OrdersClient initial={loadOf(order({}))} email="buyer@example.com" />)
    const again = screen.getByRole("link", { name: /order again/i })
    expect(again.parentElement?.closest("a")).toBeNull()
  })
})

describe("Rate your order", () => {
  it("is offered on a COMPLETED order, once per card", () => {
    render(<OrdersClient initial={loadOf(order({ orderNumber: "ORD-AAAA" }), order({ orderNumber: "ORD-BBBB", status: "COMPLETED" }))} email="buyer@example.com" />)
    expect(screen.getAllByRole("button", { name: /rate your order/i })).toHaveLength(2)
  })

  it.each(["PENDING", "PREPARING", "READY", "CANCELLED", "REFUNDED"])("is not offered on a %s order", (status) => {
    render(<OrdersClient initial={loadOf(order({ status }))} email="buyer@example.com" />)
    expect(screen.getByText("Mama Ade's Kitchen")).toBeInTheDocument()
    expect(screen.queryByRole("button", { name: /rate your order/i })).toBeNull()
  })

  it("opens the form with the public-review notice (the entry point #846 asked for)", () => {
    render(<OrdersClient initial={loadOf(order({}))} email="buyer@example.com" />)
    fireEvent.click(screen.getByRole("button", { name: /rate your order/i }))
    const form = screen.getByRole("form", { name: /rate your order/i })
    expect(within(form).getByTestId("review-public-notice")).toHaveTextContent(/first name and the first letter of your last name/i)
  })
})
