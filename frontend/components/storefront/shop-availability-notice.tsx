import { Clock } from "lucide-react"
import { availabilityNoticeCopy, readShopAvailability } from "@/lib/shop-availability"
import { cn } from "@/lib/utils"
import type { PublicShop } from "@/types/storefront"

/**
 * UI-SPEC K3 (customer side, D-15): tells the customer the shop is not taking orders, and when it
 * will, from the SERVER's answer (`lib/shop-availability.ts`). Rendered between the shop header and
 * the menu, and at the top of checkout step 1, where its `id` is the disabled Place order button's
 * permanent explanation (`aria-describedby`).
 *
 * Renders nothing when the shop is taking orders, and nothing when the server said nothing
 * (`openNow` absent): no claim the data does not back.
 *
 * Deliberately not red and not an alert: a closed shop is not an error, and the notice is page
 * content present from the first paint (server-rendered), not an event to announce. Long shop
 * names wrap (`overflow-wrap: anywhere`) rather than clip, at 360px with 130% root text.
 */
export function ShopAvailabilityNotice({
  shop,
  id,
  className,
}: {
  shop: PublicShop
  id?: string
  className?: string
}) {
  const copy = availabilityNoticeCopy(shop.name, readShopAvailability(shop))
  if (!copy) return null
  return (
    <div
      id={id}
      data-testid="shop-availability-notice"
      className={cn("flex w-full items-start gap-3 rounded-xl border border-slate-300 bg-white p-4", className)}
    >
      <Clock aria-hidden="true" className="mt-0.5 h-5 w-5 shrink-0 text-slate-600" />
      <div className="min-w-0 [overflow-wrap:anywhere]">
        <p className="text-base font-semibold text-slate-900">{copy.headline}</p>
        <p className="mt-0.5 text-sm text-slate-600">{copy.detail}</p>
      </div>
    </div>
  )
}
