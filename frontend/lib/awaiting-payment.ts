import type { PaymentStatus } from "@/types/api"

/**
 * The RFC 7807 `type` of the server's refusal to SUBMIT, CONFIRM or START_PREP an order that is still
 * awaiting its card or link payment (plan 30-05, issue #958): HTTP 409, title "Awaiting payment", and a
 * `detail` written for staff that the UI shows verbatim (UI-SPEC § S2).
 */
export const AWAITING_PAYMENT_PROBLEM_TYPE = "https://jtoye.uk/errors/awaiting-payment"

/**
 * Is this order still waiting for the customer's money?
 *
 * The server rule transcribed: `OrderService.isAwaitingUpfrontOrDepositPayment`
 * (core-java/.../order/OrderService.java l.1102-1110). It is for DISPLAY only (the "Awaiting payment"
 * badge, and the Orders list not offering Submit/Confirm); the server decides, and refuses with
 * `AWAITING_PAYMENT_PROBLEM_TYPE` whatever the client shows. Kept equal to the Java arm for arm by
 * lib/__tests__/awaiting-payment.test.ts.
 *
 *   l.1104  PENDING or FAILED                                   -> true
 *   l.1107  otherwise: a reference is set (Java `!= null`, so "" counts as set)
 *   l.1108    AND the status is not CAPTURED
 *   l.1109    AND the status is not AUTHORIZED                  -> true
 *           otherwise                                           -> false
 *
 * An absent (null / undefined) status is NOT special-cased, exactly as in Java (PR #1018 review
 * finding 8): with a reference it is awaiting payment, without one it is not. A vendor / REST / MCP
 * order (NONE, no reference) is therefore never awaiting payment (D-01).
 */
export function isAwaitingPayment(order: {
  paymentStatus?: PaymentStatus | null
  paymentReference?: string | null
}): boolean {
  const status = order.paymentStatus
  if (status === "PENDING" || status === "FAILED") {
    return true
  }
  // Loose `!= null` covers both null and undefined: Java's `!= null` on a field JSON may omit.
  return (
    order.paymentReference != null &&
    status !== "CAPTURED" &&
    status !== "AUTHORIZED"
  )
}
