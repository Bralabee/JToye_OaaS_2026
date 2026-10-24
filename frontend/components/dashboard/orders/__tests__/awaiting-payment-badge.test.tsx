/**
 * Phase 30-06 (issue #958, UI-SPEC § S2 "AwaitingPaymentBadge").
 *
 * The badge renders exactly when `isAwaitingPayment` is true — the server's own rule (plan 30-05),
 * so a cook or vendor sees BEFORE tapping that the order cannot move yet. It reads by word and shape
 * (Hourglass glyph, heavy amber border), never by colour alone, and explains itself in a title and an
 * sr-only sentence.
 *
 *   PENDING / FAILED                  -> badge
 *   reference set, not CAPTURED/AUTH  -> badge
 *   NONE with no reference (vendor)   -> nothing (D-01)
 *   status absent, no reference       -> nothing
 *   CAPTURED / AUTHORIZED             -> nothing
 */
import { render, screen } from "@testing-library/react"
import {
  AWAITING_PAYMENT_COPY,
  AWAITING_PAYMENT_TITLE,
  AwaitingPaymentBadge,
} from "@/components/dashboard/orders/awaiting-payment-badge"

describe("AwaitingPaymentBadge (UI-SPEC § S2)", () => {
  it("an order awaiting payment renders 'Awaiting payment' with the testid, title, sr text and Hourglass", () => {
    const { container } = render(
      <AwaitingPaymentBadge paymentStatus="PENDING" paymentReference="pi_123" />
    )

    const badge = screen.getByTestId("awaiting-payment")
    expect(badge).toHaveTextContent(AWAITING_PAYMENT_COPY)
    expect(AWAITING_PAYMENT_COPY).toBe("Awaiting payment")
    expect(badge).toHaveAttribute("title", AWAITING_PAYMENT_TITLE)
    expect(AWAITING_PAYMENT_TITLE).toBe(
      "The customer hasn't paid yet. This order can't be accepted or prepared until they do."
    )
    expect(screen.getByText(AWAITING_PAYMENT_TITLE)).toHaveClass("sr-only")
    // Word and shape, never colour alone (UI-SPEC colour row: amber-800 on white, 2px amber-700).
    expect(badge).toHaveClass("border-2", "border-amber-700", "bg-white", "text-amber-800")
    const glyph = container.querySelector("svg.lucide-hourglass")
    expect(glyph).not.toBeNull()
    expect(glyph).toHaveAttribute("aria-hidden", "true")
  })

  it("a link order with a reference and NONE status renders the badge", () => {
    render(<AwaitingPaymentBadge paymentStatus="NONE" paymentReference="plink_1" />)
    expect(screen.getByTestId("awaiting-payment")).toBeInTheDocument()
  })

  it("a vendor order (NONE, no reference) renders nothing (D-01)", () => {
    const { container } = render(<AwaitingPaymentBadge paymentStatus="NONE" paymentReference={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it("an absent status and no reference renders nothing", () => {
    const { container } = render(<AwaitingPaymentBadge />)
    expect(container).toBeEmptyDOMElement()
  })

  it("a captured or authorised payment renders nothing", () => {
    const captured = render(<AwaitingPaymentBadge paymentStatus="CAPTURED" paymentReference="pi_1" />)
    expect(captured.container).toBeEmptyDOMElement()
    const authorized = render(<AwaitingPaymentBadge paymentStatus="AUTHORIZED" paymentReference="pi_2" />)
    expect(authorized.container).toBeEmptyDOMElement()
  })
  // PR #1030 review round 1, finding 2: the payment rule says nothing about the ORDER's status, so a
  // REFUNDED order (paymentStatus REFUNDED, its pi_ kept) and a CANCELLED card DRAFT both read as
  // "awaiting payment". A closed order cannot be submitted, accepted or prepared at all, so the badge
  // has nothing to warn about there and would tell staff a refunded customer "hasn't paid yet".
  it("a closed order (COMPLETED, CANCELLED, REFUNDED) renders nothing, whatever its payment fields", () => {
    for (const orderStatus of ["COMPLETED", "CANCELLED", "REFUNDED"] as const) {
      const { container, unmount } = render(
        <AwaitingPaymentBadge orderStatus={orderStatus} paymentStatus="REFUNDED" paymentReference="pi_r" />
      )
      expect(container).toBeEmptyDOMElement()
      unmount()
      const pending = render(
        <AwaitingPaymentBadge orderStatus={orderStatus} paymentStatus="PENDING" paymentReference="pi_p" />
      )
      expect(pending.container).toBeEmptyDOMElement()
      pending.unmount()
    }
  })

  it("an open order awaiting payment still renders the badge when its status is given", () => {
    for (const orderStatus of ["DRAFT", "PENDING", "CONFIRMED", "PREPARING", "READY"] as const) {
      const { unmount } = render(
        <AwaitingPaymentBadge orderStatus={orderStatus} paymentStatus="PENDING" paymentReference="pi_p" />
      )
      expect(screen.getByTestId("awaiting-payment")).toBeInTheDocument()
      unmount()
    }
  })
})
