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
    expect(isVoidable({ status: "PENDING", paymentReference: "pi_123" })).toBe(true)
    expect(isVoidable({ status: "PENDING", paymentStatus: "CAPTURED", voidedAt: "2026-10-11T10:00:00Z" })).toBe(false)
  })
})
