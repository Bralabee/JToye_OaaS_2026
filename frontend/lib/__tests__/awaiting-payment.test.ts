/**
 * Phase 30-06 (issue #958, UI-SPEC § S2): `isAwaitingPayment` is the SERVER rule, transcribed.
 *
 * The server refuses SUBMIT / CONFIRM / START_PREP while
 * `OrderService.isAwaitingUpfrontOrDepositPayment` (OrderService.java l.1102-1110) is true (plan
 * 30-05). The dashboard uses the same rule for display only: the badge, and the Orders list not
 * offering Submit/Confirm. If the two drift, the UI either hides an action the server would take or
 * offers one it will refuse, so every clause of the Java method has its own case here, annotated with
 * the line it mirrors.
 *
 * The null-status arms are PR #1018 review finding 8: Java does not special-case a null status. With
 * a reference, `null != CAPTURED && null != AUTHORIZED` is true, so the order IS awaiting payment; a
 * client that short-circuits "no status -> not awaiting" would disagree with the server exactly there.
 */
import {
  AWAITING_PAYMENT_PROBLEM_TYPE,
  isAwaitingPayment,
} from "@/lib/awaiting-payment"

describe("isAwaitingPayment mirrors OrderService.isAwaitingUpfrontOrDepositPayment", () => {
  // l.1104-1105: `ps == PENDING || ps == FAILED` -> true, whatever the reference.
  it("l.1104: PENDING is awaiting payment (no reference needed)", () => {
    expect(isAwaitingPayment({ paymentStatus: "PENDING", paymentReference: null })).toBe(true)
  })

  it("l.1104: FAILED is awaiting payment (no reference needed)", () => {
    expect(isAwaitingPayment({ paymentStatus: "FAILED" })).toBe(true)
  })

  // l.1108: `ps != CAPTURED` — a captured payment with a reference is paid.
  it("l.1108: CAPTURED with a reference is not awaiting payment", () => {
    expect(isAwaitingPayment({ paymentStatus: "CAPTURED", paymentReference: "pi_x" })).toBe(false)
  })

  // l.1109: `ps != AUTHORIZED` — an authorised payment with a reference is paid.
  it("l.1109: AUTHORIZED with a reference is not awaiting payment", () => {
    expect(isAwaitingPayment({ paymentStatus: "AUTHORIZED", paymentReference: "pi_x" })).toBe(false)
  })

  // l.1107-1109: any other status WITH a reference is awaiting.
  it("l.1107: REFUNDED with a reference is awaiting payment (not CAPTURED, not AUTHORIZED)", () => {
    expect(isAwaitingPayment({ paymentStatus: "REFUNDED", paymentReference: "pi_x" })).toBe(true)
  })

  it("l.1107: NONE with a reference is awaiting payment (a link or intent was issued)", () => {
    expect(isAwaitingPayment({ paymentStatus: "NONE", paymentReference: "plink_x" })).toBe(true)
  })

  // l.1107: `getPaymentReference() != null` — Java checks null only, so an empty string counts as set.
  it("l.1107: NONE with an EMPTY reference is awaiting payment (Java checks != null only)", () => {
    expect(isAwaitingPayment({ paymentStatus: "NONE", paymentReference: "" })).toBe(true)
  })

  // l.1107: no reference -> false. D-01: a vendor / REST / MCP order (NONE, no reference) moves.
  it("l.1107: NONE with no reference is not awaiting payment (vendor orders, D-01)", () => {
    expect(isAwaitingPayment({ paymentStatus: "NONE", paymentReference: null })).toBe(false)
    expect(isAwaitingPayment({ paymentStatus: "NONE" })).toBe(false)
  })

  // The null-status arms (PR #1018 review finding 8): no special case for an absent status.
  it("l.1107-1109: a null status WITH a reference is awaiting payment (null is neither CAPTURED nor AUTHORIZED)", () => {
    expect(isAwaitingPayment({ paymentStatus: null, paymentReference: "pi_x" })).toBe(true)
  })

  it("l.1107-1109: an undefined status WITH a reference is awaiting payment", () => {
    expect(isAwaitingPayment({ paymentReference: "pi_x" })).toBe(true)
  })

  it("l.1107: a null status with no reference is not awaiting payment", () => {
    expect(isAwaitingPayment({ paymentStatus: null, paymentReference: null })).toBe(false)
  })

  it("l.1107: an undefined status with a null reference is not awaiting payment", () => {
    expect(isAwaitingPayment({ paymentReference: null })).toBe(false)
    expect(isAwaitingPayment({})).toBe(false)
  })
})

describe("AWAITING_PAYMENT_PROBLEM_TYPE", () => {
  it("is the RFC 7807 type the server's 409 carries (GlobalExceptionHandler, plan 30-05)", () => {
    expect(AWAITING_PAYMENT_PROBLEM_TYPE).toBe("https://jtoye.uk/errors/awaiting-payment")
  })
})
