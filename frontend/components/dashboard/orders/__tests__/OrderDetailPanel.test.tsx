/**
 * Tests for OrderDetailPanel (Phase 17-04 / VOPS-01).
 *
 * Validates:
 *   - All five blocks render for a CONFIRMED order with no refunds
 *   - Refund history renders one row per refund with status colour classes
 *   - "Issue refund" button is hidden on a DRAFT order (status not refundable)
 *   - "Issue refund" button is hidden on a REFUNDED order with full refund
 *     (remaining = 0)
 *   - "Issue refund" button is shown when status, paymentStatus,
 *     paymentReference, and remaining are all valid
 */

import { render, screen } from "@testing-library/react"
import { OrderDetailPanel } from "../OrderDetailPanel"
import type { OrderDetail, Refund } from "@/types/api"

// RefundDialog is a child of OrderDetailPanel — stub it so these tests stay
// focused on the panel's own rendering and visibility logic.
jest.mock("../RefundDialog", () => ({
  RefundDialog: ({ open }: { open: boolean }) =>
    open ? <div data-testid="refund-dialog-stub" /> : null,
}))

function makeOrder(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: "order-abc-123",
    tenantId: "tenant-xyz",
    shopId: "shop-1",
    orderNumber: "ORD-2026-0001",
    status: "CONFIRMED",
    customerName: "Jane Doe",
    customerEmail: "jane@example.com",
    customerPhone: "+44 7700 900000",
    notes: "No nuts please",
    totalAmountPennies: 1000,
    items: [
      {
        id: "item-1",
        productId: "prod-1",
        productName: "Jollof Rice",
        quantity: 2,
        unitPricePennies: 500,
        totalPricePennies: 1000,
        createdAt: "2026-04-28T10:00:00Z",
      },
    ],
    createdAt: "2026-04-28T10:00:00Z",
    updatedAt: "2026-04-28T10:00:00Z",
    paymentStatus: "CAPTURED",
    paymentReference: "pi_test_123",
    paymentMethod: "card",
    refunds: [],
    ...overrides,
  }
}

function makeRefund(overrides: Partial<Refund> = {}): Refund {
  return {
    id: "refund-1",
    tenantId: "tenant-xyz",
    orderId: "order-abc-123",
    stripeRefundId: "re_test_123",
    idempotencyKey: "abc-key",
    amountPennies: 500,
    currency: "gbp",
    reason: "REQUESTED_BY_CUSTOMER",
    reasonNote: "Missing item",
    status: "succeeded",
    failureReason: null,
    requestedAt: "2026-04-28T11:00:00Z",
    updatedAt: "2026-04-28T11:00:00Z",
    ...overrides,
  }
}

describe("OrderDetailPanel", () => {
  it("renders header, customer, payment, items, and action blocks", () => {
    render(<OrderDetailPanel order={makeOrder()} />)

    // Header block — order number and a confirmed-status badge. The £10.00
    // total appears twice (header total + line item total) so we use
    // getAllByText for that one.
    expect(screen.getByText("ORD-2026-0001")).toBeInTheDocument()
    expect(screen.getAllByText("£10.00").length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText("Confirmed")).toBeInTheDocument()

    // Customer block
    expect(screen.getByText("Jane Doe")).toBeInTheDocument()
    expect(screen.getByText("jane@example.com")).toBeInTheDocument()
    expect(screen.getByText("+44 7700 900000")).toBeInTheDocument()

    // Payment block
    expect(screen.getByText("CAPTURED")).toBeInTheDocument()
    expect(screen.getByText("card")).toBeInTheDocument()
    expect(screen.getByText("pi_test_123")).toBeInTheDocument()

    // Line items block — renders the real product name (from the snapshotted
    // OrderItem.productName populated by 19-01), qty, and unit price.
    expect(screen.getByText(/Items \(1\)/)).toBeInTheDocument()
    expect(screen.getByText("Jollof Rice")).toBeInTheDocument()

    // Action panel — "Issue refund" is visible because status=CONFIRMED,
    // payment=CAPTURED, paymentReference is set, remaining > 0.
    expect(
      screen.getByRole("button", { name: /Issue refund/i })
    ).toBeInTheDocument()
  })

  it("never renders 'Unknown Product' for a line item that references a real product (#2)", () => {
    render(<OrderDetailPanel order={makeOrder()} />)
    // The snapshotted productName renders; the last-resort "Unknown Product"
    // fallback (backlog #2) must never appear for an existing product.
    expect(screen.getByText("Jollof Rice")).toBeInTheDocument()
    expect(screen.queryByText("Unknown Product")).not.toBeInTheDocument()
  })

  it("renders the delivery-address block for a DELIVERY order", () => {
    render(
      <OrderDetailPanel
        order={makeOrder({
          fulfilmentType: "DELIVERY",
          addressLine1: "12 Rye Lane",
          addressLine2: "Flat 2",
          addressCity: "London",
          addressPostcode: "SE15 5BS",
        })}
      />
    )
    expect(screen.getByTestId("delivery-address")).toBeInTheDocument()
    expect(screen.getByText("Delivery")).toBeInTheDocument()
    expect(screen.getByText("12 Rye Lane")).toBeInTheDocument()
    expect(screen.getByText("Flat 2")).toBeInTheDocument()
    expect(screen.getByText("London")).toBeInTheDocument()
    expect(screen.getByText("SE15 5BS")).toBeInTheDocument()
  })

  it("omits the delivery-address block for a COLLECTION order", () => {
    render(
      <OrderDetailPanel order={makeOrder({ fulfilmentType: "COLLECTION" })} />
    )
    expect(screen.queryByTestId("delivery-address")).not.toBeInTheDocument()
    // The fulfilment label still shows so the vendor knows it is a collection.
    expect(screen.getByText("Collection")).toBeInTheDocument()
  })

  it("renders refund history when refunds.length > 0", () => {
    const refunds = [
      makeRefund({ id: "r1", amountPennies: 300, status: "succeeded" }),
      makeRefund({ id: "r2", amountPennies: 200, status: "pending" }),
    ]
    render(<OrderDetailPanel order={makeOrder({ refunds })} />)

    expect(screen.getByText(/Refunds \(2\)/)).toBeInTheDocument()
    expect(screen.getByText("£3.00")).toBeInTheDocument()
    expect(screen.getByText("£2.00")).toBeInTheDocument()
    // Status colour-coded text exists for both
    expect(screen.getByText("succeeded")).toHaveClass("text-emerald-700")
    // orange-700, not -600: orange-600 is 3.56:1 on white and failed AA (451).
    expect(screen.getByText("pending")).toHaveClass("text-orange-700")
  })

  it("hides 'Issue refund' button on a DRAFT order", () => {
    render(
      <OrderDetailPanel
        order={makeOrder({ status: "DRAFT", paymentStatus: "NONE" })}
      />
    )
    expect(
      screen.queryByRole("button", { name: /Issue refund/i })
    ).not.toBeInTheDocument()
  })

  it("hides 'Issue refund' button when paymentStatus !== CAPTURED", () => {
    render(
      <OrderDetailPanel
        order={makeOrder({ paymentStatus: "AUTHORIZED" })}
      />
    )
    expect(
      screen.queryByRole("button", { name: /Issue refund/i })
    ).not.toBeInTheDocument()
  })

  it("hides 'Issue refund' button when paymentReference is missing", () => {
    render(
      <OrderDetailPanel
        order={makeOrder({ paymentReference: null })}
      />
    )
    expect(
      screen.queryByRole("button", { name: /Issue refund/i })
    ).not.toBeInTheDocument()
  })

  it("hides 'Issue refund' on a REFUNDED order with full refund (remaining = 0)", () => {
    const refunds = [
      makeRefund({ id: "r1", amountPennies: 1000, status: "succeeded" }),
    ]
    render(
      <OrderDetailPanel
        order={makeOrder({ status: "REFUNDED", refunds })}
      />
    )
    expect(
      screen.queryByRole("button", { name: /Issue refund/i })
    ).not.toBeInTheDocument()
    // The refund history still shows the row — vendors can see what happened.
    expect(screen.getByText(/Refunds \(1\)/)).toBeInTheDocument()
    // And the REFUNDED badge appears
    expect(screen.getByText("Refunded")).toBeInTheDocument()
  })

  it("shows 'Issue refund' button when status COMPLETED and remaining > 0 after partial refund", () => {
    const refunds = [
      makeRefund({ id: "r1", amountPennies: 300, status: "succeeded" }),
    ]
    render(
      <OrderDetailPanel
        order={makeOrder({ status: "COMPLETED", refunds })}
      />
    )
    expect(
      screen.getByRole("button", { name: /Issue refund/i })
    ).toBeInTheDocument()
    // Footer shows the running totals for transparency
    expect(screen.getByText(/Already refunded: £3\.00/)).toBeInTheDocument()
    expect(screen.getByText(/Remaining:\s*£7\.00/)).toBeInTheDocument()
  })

  it("QA-council F3 / A11Y-1: PENDING status badge is bg-yellow-700, not the failing bg-yellow-500", () => {
    render(
      <OrderDetailPanel order={makeOrder({ status: "PENDING", paymentStatus: "NONE" })} />
    )
    const badge = screen.getByText("Pending")
    expect(badge).toHaveClass("bg-yellow-700")
    expect(badge).not.toHaveClass("bg-yellow-500")
  })

  it("renders failed refund failure_reason in the history row", () => {
    const refunds = [
      makeRefund({
        id: "r1",
        amountPennies: 500,
        status: "failed",
        failureReason: "card_declined",
      }),
    ]
    render(<OrderDetailPanel order={makeOrder({ refunds })} />)

    expect(screen.getByText("failed")).toHaveClass("text-red-600")
    expect(screen.getByText("card_declined")).toBeInTheDocument()
  })

  // QA-council FE-7 (V63 / Phase 31-10): the order-detail vendor view did not
  // surface the write-time allergen snapshot at all, despite `OrderDetailDto`
  // already carrying it and the kitchen display already rendering it. NULL
  // ("not recorded") and [] ("declared none of the 14") must render
  // differently — see the shared `OrderAllergenBanner`'s own docstring for why.
  describe("QA-council FE-7: allergen snapshot", () => {
    it("shows NOT RECORDED when allergenNames is absent — never claims allergen-free", () => {
      render(<OrderDetailPanel order={makeOrder()} />)
      expect(screen.getByTestId("kds-allergen-unrecorded")).toBeInTheDocument()
      expect(screen.getByText("ALLERGENS NOT RECORDED")).toBeInTheDocument()
      expect(screen.queryByTestId("kds-allergen-banner")).not.toBeInTheDocument()
    })

    it("renders nothing when the vendor declared none of the 14 and nothing was flagged", () => {
      render(<OrderDetailPanel order={makeOrder({ allergenNames: [], allergenFlags: [] })} />)
      expect(screen.queryByTestId("kds-allergen-unrecorded")).not.toBeInTheDocument()
      expect(screen.queryByTestId("kds-allergen-banner")).not.toBeInTheDocument()
    })

    it("renders the declared set when allergenNames is non-empty", () => {
      render(
        <OrderDetailPanel
          order={makeOrder({ allergenNames: ["Gluten", "Milk"], allergenFlags: [] })}
        />
      )
      expect(screen.getByTestId("kds-allergen-banner")).toBeInTheDocument()
      expect(screen.getByTestId("kds-allergen-declared")).toHaveTextContent("Gluten, Milk")
    })

    it("renders an advisory CHECK line for a flag WITHOUT merging it into the declared set", () => {
      render(
        <OrderDetailPanel
          order={makeOrder({
            allergenNames: ["Gluten"],
            allergenFlags: [
              { productName: "Jollof Rice", allergenBit: 6, allergenName: "Milk" },
            ],
          })}
        />
      )
      // The declared line names ONLY the declared set — "Milk" appears solely
      // in the advisory CHECK line, never folded into the declaration.
      expect(screen.getByTestId("kds-allergen-declared")).toHaveTextContent("Gluten")
      expect(screen.getByTestId("kds-allergen-declared")).not.toHaveTextContent("Milk")
      expect(screen.getByTestId("kds-allergen-check")).toHaveTextContent(
        "CHECK: Jollof Rice"
      )
    })

    it("renders a per-item allergen badge from the line's own snapshot", () => {
      render(
        <OrderDetailPanel
          order={makeOrder({
            allergenNames: ["Gluten"],
            items: [
              {
                id: "item-1",
                productId: "prod-1",
                productName: "Jollof Rice",
                quantity: 2,
                unitPricePennies: 500,
                totalPricePennies: 1000,
                createdAt: "2026-04-28T10:00:00Z",
                allergenNames: ["Gluten"],
              },
            ],
          })}
        />
      )
      expect(screen.getByTestId("kds-item-allergen-badge")).toBeInTheDocument()
    })

    it("renders no per-item badge when a line predates the snapshot (allergenNames absent)", () => {
      render(<OrderDetailPanel order={makeOrder({ allergenNames: ["Gluten"] })} />)
      expect(screen.queryByTestId("kds-item-allergen-badge")).not.toBeInTheDocument()
    })
  })
})
