"use client"

import { Store, Info } from "lucide-react"

/**
 * Says, unmissably, whose tickets are on this board (#450 sub-item 5d).
 *
 * THE DEFECT. In the All-shops context the board fell back to `shops[0].id`
 * (`kitchen/page.tsx:209-222`) and rendered that one shop's tickets. Nothing said so.
 * Measured on the live stack on 2026-08-04 with three published shops: the sidebar
 * switcher read "All shops", the board issued exactly one request —
 * `?shopId=97d95aa4…` (Brixton Village Grill) — and every ticket on screen was
 * Brixton's. The other two shops' kitchens were invisible, and the interface asserted
 * the opposite. That is a trust defect, not a cosmetic one: a vendor reading an empty
 * board concludes there are no orders.
 *
 * THE DECISION — label, not aggregate. Full reasoning is in the PR; the short form is
 * that a KDS is a physical screen in ONE kitchen, the live subscription
 * (`/topic/kitchen.{tenant}.{shop}`) is per shop, and merging three shops' tickets
 * onto one board would put Peckham's orders in front of Brixton's cooks. So the board
 * stays single-shop — which it already was — and stops pretending otherwise.
 *
 * Two parts, because they answer different questions:
 *   `KdsBoardShopName`  what am I looking at?      — always on, in the header.
 *   `KdsAllShopsNotice` why isn't this all of it?  — only in the All-shops context.
 */

export function KdsBoardShopName({ shopName }: { shopName: string | null }) {
  return (
    <p
      data-testid="kds-board-shop"
      className="mt-1 flex items-center gap-2 text-slate-600"
    >
      <Store aria-hidden className="h-4 w-4 flex-shrink-0" />
      <span>
        {shopName ? (
          <>
            Showing tickets for{" "}
            <span className="font-semibold text-slate-900">{shopName}</span>
          </>
        ) : (
          "No shop selected"
        )}
      </span>
    </p>
  )
}

export function KdsAllShopsNotice({
  shopName,
  shopCount,
}: {
  shopName: string | null
  shopCount: number
}) {
  if (!shopName) return null
  return (
    <div
      // `status`, not `alert`: nothing is broken and nothing needs doing urgently.
      // Escalating this to an interruption would blunt the real `alert` next to it.
      role="status"
      data-testid="kds-all-shops-notice"
      className="flex items-start gap-3 rounded-lg border border-slate-300 bg-slate-50 px-4 py-3 text-slate-800"
    >
      <Info aria-hidden className="mt-0.5 h-5 w-5 flex-shrink-0 text-slate-500" />
      <p className="text-sm leading-relaxed">
        Your dashboard is set to <span className="font-semibold">All shops</span>, but
        a kitchen board shows <span className="font-semibold">one shop at a time</span>
        . These are{" "}
        <span className="font-semibold text-slate-900">{shopName}</span>&rsquo;s
        tickets only
        {shopCount > 1 ? (
          <>
            {" "}
            &mdash; orders for your{" "}
            {/* "your other 1 shop" is the `"1 items"` defect in #450 item 5 wearing a
                different hat. The count is only worth printing when it is >1. */}
            {shopCount === 2 ? "other shop" : `other ${shopCount - 1} shops`} are not
            on this screen. Use the shop selector to switch.
          </>
        ) : (
          "."
        )}
      </p>
    </div>
  )
}
