import { isVoidable } from "../void-order-dialog"

// Review #913 round 1, finding 1 (D-10): a card payment settles while the order is still PENDING, so the
// server refuses to delete such an order and voids it instead. The dialog's predicate must offer the void
// the server accepts, and keep refusing it where the server would answer 409 order-not-voidable.
describe("isVoidable", () => {
  it("offers a void from CONFIRMED on, and never for an order already voided", () => {
    expect(isVoidable({ status: "CONFIRMED" })).toBe(true)
    expect(isVoidable({ status: "COMPLETED", voidedAt: null })).toBe(true)
    expect(isVoidable({ status: "COMPLETED", voidedAt: "2026-10-11T10:00:00Z" })).toBe(false)
  })

  it("refuses a DRAFT or PENDING order that was never paid (the server says delete it instead)", () => {
    expect(isVoidable({ status: "DRAFT" })).toBe(false)
    expect(isVoidable({ status: "PENDING", paymentStatus: "NONE", paymentReference: null })).toBe(false)
  })

  it("offers a void for a PENDING order whose card payment was captured", () => {
    expect(isVoidable({ status: "PENDING", paymentStatus: "CAPTURED" })).toBe(true)
    expect(isVoidable({ status: "PENDING", paymentStatus: "CAPTURED", voidedAt: "2026-10-11T10:00:00Z" })).toBe(false)
  })
})

// Round 2 of review #913: the storefront writes paymentReference when the PaymentIntent is CREATED
// (order DRAFT, payment PENDING) and again when it FAILS, so a reference alone does not mean money
// moved. The server's isSettled keys on a captured payment or a SALE row, never on the reference.
describe("isVoidable mirrors the server's settled test", () => {
  it("refuses an abandoned or declined card checkout: a reference with no captured payment", () => {
    expect(isVoidable({ status: "DRAFT", paymentStatus: "PENDING", paymentReference: "pi_abandoned" })).toBe(false)
    expect(isVoidable({ status: "DRAFT", paymentStatus: "FAILED", paymentReference: "pi_declined" })).toBe(false)
    expect(isVoidable({ status: "PENDING", paymentReference: "pi_only" })).toBe(false)
  })

  it("offers the void once the payment was captured, refunded or not", () => {
    expect(isVoidable({ status: "PENDING", paymentStatus: "CAPTURED", paymentReference: "pi_1" })).toBe(true)
    expect(isVoidable({ status: "PENDING", paymentStatus: "REFUNDED", paymentReference: "pi_1" })).toBe(true)
  })
})
