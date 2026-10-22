// RED stub (30-06): exports exist so the suites load and fail on assertions. Replaced at GREEN.
import type { PaymentStatus } from "@/types/api"

export const AWAITING_PAYMENT_COPY = ""
export const AWAITING_PAYMENT_TITLE = ""

export function AwaitingPaymentBadge(_props: {
  paymentStatus?: PaymentStatus | null
  paymentReference?: string | null
  className?: string
}) {
  return null
}
