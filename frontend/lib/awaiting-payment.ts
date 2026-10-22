// RED stub (30-06): exports exist so the suites load and fail on assertions. Replaced at GREEN.
import type { PaymentStatus } from "@/types/api"

export const AWAITING_PAYMENT_PROBLEM_TYPE = ""

export function isAwaitingPayment(_order: {
  paymentStatus?: PaymentStatus | null
  paymentReference?: string | null
}): boolean {
  return false
}
