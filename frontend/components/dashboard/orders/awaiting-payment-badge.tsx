import { Hourglass } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { isAwaitingPayment } from "@/lib/awaiting-payment"
import { cn } from "@/lib/utils"
import type { PaymentStatus } from "@/types/api"

/**
 * AwaitingPaymentBadge — 30-06 (issue #958, UI-SPEC § S2 "AwaitingPaymentBadge").
 *
 * Marks an order the server will not let anyone submit, accept or prepare yet, because the customer's
 * card or link payment has not completed (plan 30-05). It warns BEFORE a tap; the server's refusal
 * explains AFTER one. Shown on New-lane cards, grid cards and Orders-list rows.
 *
 * It renders exactly when `isAwaitingPayment` is true (the server rule, transcribed), and NOTHING for a
 * vendor order (NONE, no reference — D-01) or a paid one.
 *
 * White with a heavy amber border and the Hourglass glyph, so it reads by word and shape, never by
 * colour alone (the VoidedBadge / UnverifiedContactBadge precedent).
 */

export const AWAITING_PAYMENT_COPY = "Awaiting payment"
export const AWAITING_PAYMENT_TITLE =
  "The customer hasn't paid yet. This order can't be accepted or prepared until they do."

export function AwaitingPaymentBadge({
  paymentStatus,
  paymentReference,
  className,
}: {
  paymentStatus?: PaymentStatus | null
  paymentReference?: string | null
  className?: string
}) {
  if (!isAwaitingPayment({ paymentStatus, paymentReference })) return null
  return (
    <Badge
      variant="outline"
      data-testid="awaiting-payment"
      title={AWAITING_PAYMENT_TITLE}
      className={cn(
        "flex w-fit items-center gap-1 border-2 border-amber-700 bg-white text-amber-800",
        className
      )}
    >
      <Hourglass className="h-3 w-3" aria-hidden="true" />
      <span>{AWAITING_PAYMENT_COPY}</span>
      <span className="sr-only">{AWAITING_PAYMENT_TITLE}</span>
    </Badge>
  )
}

export default AwaitingPaymentBadge
