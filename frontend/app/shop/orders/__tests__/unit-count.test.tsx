/**
 * COR-4 (QA-council 20260902-134741, adjudication A9) — "items" must mean the same thing on the
 * customer's order history as it does in their basket.
 *
 * `itemCount` is LINES on the server (Order.calculateTotal -> items.size()) and UNITS in the
 * browser (cart-provider reduces over quantity), and both render the identical English word. A
 * customer who buys 6 Zobos was shown "6 items" in the basket and "1 item" here, minutes later.
 * Live on 24 of 60 dev orders.
 *
 * The fix is additive: V66's nullable `unitCount` beside the untouched `itemCount`. These tests
 * pin the three states that matter — recorded units, a pre-V66 row where the count is NOT
 * RECORDED, and the guarantee that the LINE count never appears under the word "items".
 */
import { render, screen } from "@testing-library/react"
import { OrdersClient } from "../orders-client"
import type { OrderSummary, OrdersLoad } from "@/lib/customer-orders"

jest.mock("@/lib/customer-auth", () => ({
  getCustomerSession: () => ({ email: "buyer@example.com" }),
}))

function order(partial: Partial<OrderSummary>): OrderSummary {
  return {
    orderNumber: "ORD-00000000-20260902-AAAAAAAA",
    status: "PREPARING",
    shopName: "Brixton Kitchen",
    totalAmountPennies: 5394,
    itemCount: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...partial,
  }
}

function loadOf(...orders: OrderSummary[]): OrdersLoad {
  return { state: "ok", orders, totalElements: orders.length }
}

describe("COR-4: the customer's order history counts UNITS, like the basket did", () => {
  it("renders the recorded unit count, NOT the line count", () => {
    render(
      <OrdersClient
        initial={loadOf(order({ itemCount: 1, unitCount: 6 }))}
        email="buyer@example.com"
      />,
    )

    expect(screen.getByText(/6 items/)).toBeInTheDocument()
    // The precise defect: the LINE count must never be printed under the word "items".
    expect(screen.queryByText(/\b1 item\b/)).not.toBeInTheDocument()
  })

  it("says nothing at all when the count was NOT RECORDED — a pre-V66 row", () => {
    render(
      <OrdersClient
        initial={loadOf(order({ itemCount: 1, unitCount: null }))}
        email="buyer@example.com"
      />,
    )

    // No count is printed. Falling back to itemCount would reprint the defect; coalescing null
    // to 0 would claim an empty order. The price still renders, which is the figure the customer
    // actually needs.
    expect(screen.queryByText(/\d+ items?/)).not.toBeInTheDocument()
    expect(screen.getByText(/53\.94/)).toBeInTheDocument()
  })

  it("singularises a genuine single unit", () => {
    render(
      <OrdersClient
        initial={loadOf(order({ itemCount: 1, unitCount: 1 }))}
        email="buyer@example.com"
      />,
    )

    expect(screen.getByText(/1 item\b/)).toBeInTheDocument()
  })
})
