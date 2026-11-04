import { Banknote, CreditCard } from "lucide-react"

import { cn } from "@/lib/utils"
import { onlyOfferedType, type OfferedFlags } from "@/lib/fulfilment-offered"
import type { FulfilmentType } from "@/types/api"

/**
 * #829 (UXT-060, persona close-out 2026-11-04): how the customer pays, said where they choose.
 *
 * Until now the only statement of it was the "How you'll pay" block at the bottom of checkout, so a
 * customer expecting a card learned the shop was cash-only after building a basket, and the Place
 * order button still wore a card icon. This is the ONE reading every earlier surface uses (the shop
 * card, the shop's own page, the basket, the top of checkout), so they cannot disagree.
 *
 * THE SOURCE IS THE SERVER'S. `acceptsCardPayments` is `PaymentService.isConfigured()`, the same gate
 * `createGuestOrder` uses to choose a card intent over cash on delivery (PublicStorefrontService). The
 * client keeps no rule of its own, and an ABSENT flag (an older backend) says NOTHING: it is not
 * "cash", because claiming cash for a shop that takes cards is exactly the wrong-way error. The
 * checkout's existing "How you'll pay" block keeps its own absent-field rule for the same reason.
 *
 * Cash is worded per fulfilment type: a shop that only collects never says "delivery" (the discovery
 * card test pins that), and once the customer has picked a type at checkout the label follows it.
 */

export interface PaymentMethod {
  kind: "card" | "cash"
  /** Short, chip-sized. */
  label: string
  /** One sentence for the basket and checkout, where there is room. */
  detail: string
}

/** The flags this reads from a `PublicShop`. */
export interface PaymentMethodShop extends OfferedFlags {
  acceptsCardPayments?: boolean | null
}

const CASH_LABEL: Record<FulfilmentType | "BOTH", string> = {
  COLLECTION: "Cash on collection",
  DELIVERY: "Cash on delivery",
  BOTH: "Cash on collection or delivery",
}

const CASH_DETAIL: Record<FulfilmentType | "BOTH", string> = {
  COLLECTION: "You pay the shop in cash when you collect. Nothing is charged online.",
  DELIVERY: "You pay the driver in cash. Nothing is charged online.",
  BOTH: "You pay in cash, to the driver or at the shop. Nothing is charged online.",
}

const CARD_DETAIL = "You pay by card online, once you have placed the order."

/**
 * The method the shop takes, or null when the server did not say.
 *
 * @param fulfilment the customer's choice where one exists (checkout). Without it, a shop taking one
 *                   fulfilment type is worded for that type and one taking both for both.
 */
export function paymentMethodFor(
  shop: PaymentMethodShop | null | undefined,
  fulfilment?: FulfilmentType | null
): PaymentMethod | null {
  if (!shop || typeof shop.acceptsCardPayments !== "boolean") return null
  if (shop.acceptsCardPayments) {
    return { kind: "card", label: "Card payment", detail: CARD_DETAIL }
  }
  const type: FulfilmentType | "BOTH" = fulfilment ?? onlyOfferedType(shop) ?? "BOTH"
  return { kind: "cash", label: CASH_LABEL[type], detail: CASH_DETAIL[type] }
}

function MethodIcon({ kind, className }: { kind: PaymentMethod["kind"]; className?: string }) {
  const Icon = kind === "card" ? CreditCard : Banknote
  return <Icon className={className} aria-hidden="true" />
}

/**
 * The compact form: an icon and the label, for a card's meta row or a details bar. Renders nothing
 * when the server did not say. Colour is inherited, so it reads on white and on the hero alike.
 */
export function PaymentMethodChip({
  shop,
  fulfilment,
  className,
}: {
  shop: PaymentMethodShop | null | undefined
  fulfilment?: FulfilmentType | null
  className?: string
}) {
  const method = paymentMethodFor(shop, fulfilment)
  if (!method) return null
  return (
    <span data-testid="payment-method-chip" className={cn("inline-flex items-center gap-1", className)}>
      <MethodIcon kind={method.kind} className="h-3.5 w-3.5 flex-shrink-0" />
      {method.label}
    </span>
  )
}

/**
 * The full form: label and one sentence, for the basket and the top of checkout. A plain block with no
 * live region: it is part of the page, not an event, and it does not change while the customer reads
 * it (checkout re-words it when they switch delivery and collection, which they asked for).
 */
export function PaymentMethodNote({
  shop,
  fulfilment,
  className,
}: {
  shop: PaymentMethodShop | null | undefined
  fulfilment?: FulfilmentType | null
  className?: string
}) {
  const method = paymentMethodFor(shop, fulfilment)
  if (!method) return null
  return (
    <div
      data-testid="payment-method-note"
      className={cn(
        "flex items-start gap-2 rounded-xl border border-cream-100 bg-white px-4 py-3 text-sm shadow-sm",
        className
      )}
    >
      <MethodIcon kind={method.kind} className="mt-0.5 h-4 w-4 flex-shrink-0 text-slate-600" />
      <p className="min-w-0 text-slate-600">
        <span className="font-semibold text-slate-900">{method.label}.</span> {method.detail}
      </p>
    </div>
  )
}
