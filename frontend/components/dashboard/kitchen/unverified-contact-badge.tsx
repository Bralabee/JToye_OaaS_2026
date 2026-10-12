import { UserX } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"

/**
 * UnverifiedContactBadge — 37-30 (UI-SPEC § K1 "Card layout", § D1 "Unverified badge"; D-18; F20).
 *
 * Shown beside the customer name wherever an order renders (kitchen cards, the Orders list, the
 * order detail) when the STORED fact `contactVerified` is `false`: the storefront request carried
 * no verified customer token for its email (37-27, V81 `orders.contact_verified`), so the kitchen
 * knows this contact has not been confirmed before it starts cooking a cash order.
 *
 * It renders NOTHING for `true` (confirmed — there is no badge to earn) and NOTHING for `null` or
 * an absent field, which is NOT RECORDED: every vendor, REST and MCP order, and every order placed
 * before V81. "Not recorded" must never read as "unverified". The badge is therefore never derived
 * from the absence of a token or of a customer account — only from the stored `false` (F20).
 *
 * White with a dashed slate border and the UserX glyph, so it reads by word and shape, never by
 * colour alone (the VoidedBadge precedent).
 */

export const UNVERIFIED_CONTACT_COPY = "Unverified contact"
export const UNVERIFIED_CONTACT_TITLE = "This customer's email or phone hasn't been confirmed."

export function UnverifiedContactBadge({
  contactVerified,
  className,
}: {
  contactVerified?: boolean | null
  className?: string
}) {
  if (contactVerified !== false) return null
  return (
    <Badge
      variant="outline"
      data-testid="unverified-contact"
      title={UNVERIFIED_CONTACT_TITLE}
      className={cn(
        "flex w-fit items-center gap-1 border border-dashed border-slate-500 bg-white text-slate-700",
        className
      )}
    >
      <UserX className="h-3 w-3" aria-hidden="true" />
      <span>{UNVERIFIED_CONTACT_COPY}</span>
      <span className="sr-only">{UNVERIFIED_CONTACT_TITLE}</span>
    </Badge>
  )
}

export default UnverifiedContactBadge
