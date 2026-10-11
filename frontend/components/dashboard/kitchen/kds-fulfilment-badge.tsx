import { Bike, Store } from "lucide-react"
import type { FulfilmentType } from "@/types/api"

/**
 * How the order leaves the kitchen, beside the order number on every kitchen card (Phase 37-16,
 * UXT-011; UI-SPEC § K1 "Card additions").
 *
 * The printed ticket has always said DELIVERY or COLLECTION (kitchen-ticket.tsx); the screen said
 * nothing, so a cook bagging an order could not tell whether a rider or the customer was coming for it.
 *
 * THE TWO VALUES DIFFER BY ICON AND WORD, NEVER BY COLOUR (L7: colour is never the only channel on the
 * kitchen board). One treatment for both: white, a 2px slate-900 border, slate-900 text, 14px/600
 * uppercase with tracking, because it is a two-value code read at 0.6-1.5 m. It borrows neither the
 * amber-800 allergen fill nor the slate-900 allergy-note fill (L6), so it never reads as a warning.
 *
 * `flex-shrink-0`: the badge keeps its width and the order number beside it wraps instead (the number
 * is the one thing on the card that must be read whole).
 *
 * An order with no fulfilment type renders nothing rather than a guess: every order the API serves
 * carries one (V45 defaults it), so absence means an older or partial payload, and a wrong badge would
 * send food out the wrong door.
 */
export function KdsFulfilmentBadge({ fulfilmentType }: { fulfilmentType?: FulfilmentType | null }) {
  if (fulfilmentType !== "DELIVERY" && fulfilmentType !== "COLLECTION") return null
  const isDelivery = fulfilmentType === "DELIVERY"
  const Icon = isDelivery ? Bike : Store
  return (
    <span
      data-testid="kds-fulfilment-badge"
      className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-md border-2 border-slate-900 bg-white px-2 py-0.5 text-sm font-semibold uppercase tracking-[0.08em] text-slate-900"
    >
      <Icon aria-hidden="true" className="h-4 w-4" />
      {isDelivery ? "DELIVERY" : "COLLECTION"}
    </span>
  )
}
