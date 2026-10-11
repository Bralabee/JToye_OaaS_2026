import { ItemAllergenBadge } from "@/components/dashboard/kitchen/item-allergen-badge"
import type { OrderDetail } from "@/types/api"

/**
 * Pieces every kitchen card renders the same way, whether it sits in the New lane or the board grid
 * (Phase 37-16). They lived inline in `app/dashboard/kitchen/page.tsx`; a Next page module may only
 * export its route members, so the New lane could not import them from there, and a second copy would
 * be a second answer waiting to disagree. Moved, not changed: markup, classes and test ids are the ones
 * the board has shipped since 31-15 and #12.
 */

/**
 * Cap/format the elapsed time so a stale order never renders raw uncapped minutes (the old
 * "2245m ago" bug, backlog #12): <1m -> "just now", <60m -> "Xm ago", <24h -> "Xh ago", >=24h -> "Xd ago".
 */
export function elapsedText(createdAt: string): string {
  const totalMinutes = Math.floor((Date.now() - new Date(createdAt).getTime()) / 60000)
  if (totalMinutes < 1) return "just now"
  if (totalMinutes < 60) return `${totalMinutes}m ago`
  const totalHours = Math.floor(totalMinutes / 60)
  if (totalHours < 24) return `${totalHours}h ago`
  const totalDays = Math.floor(totalHours / 24)
  return `${totalDays}d ago`
}

/** The order label staff say out loud: the ORD-… number, or a short id for an order without one. */
export function orderLabel(order: Pick<OrderDetail, "orderNumber" | "id">): string {
  return order.orderNumber || `#${order.id.substring(0, 6)}`
}

/**
 * The "{n} items" summary plus one row per item with its allergen badge (31-15).
 *
 * THE "{n} items" SUMMARY STAYS beside the list: it is the one line that answers "how big is this
 * ticket" at a glance, and the list is new capacity, not a replacement for it. One row per item at
 * 14px, because a 12px inline run cannot carry a per-item allergen badge legibly at the 0.6-1.5 m a
 * kitchen display is read from.
 */
export function KdsItemList({ items }: { items: OrderDetail["items"] | undefined }) {
  const itemSummary =
    items && items.length > 0
      ? `${items.length} item${items.length !== 1 ? "s" : ""}`
      : "No items"
  return (
    <div className="text-sm text-slate-600">
      <span className="font-medium">{itemSummary}</span>
      {items && items.length > 0 && (
        <ul data-testid="kds-item-list" className="mt-1 space-y-1 text-sm text-slate-600">
          {items.map((item, i) => (
            <li key={item.id || i} className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
              <span className="font-medium">{item.quantity}x</span>
              <span>{item.productName}</span>
              {/* Truncates at 3 names + "+N". Safe ONLY because the banner in the header above
                  carries the full set. */}
              <ItemAllergenBadge allergenNames={item.allergenNames} />
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
