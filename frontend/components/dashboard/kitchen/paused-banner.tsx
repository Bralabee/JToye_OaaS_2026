"use client"

import { PauseCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import { formatUkTime } from "@/lib/shop-availability"
import type { PauseView } from "@/components/dashboard/kitchen/pause-orders-control"

interface PausedBannerProps {
  /** The board's shop, named so a wall screen showing one of several shops says whose orders stopped. */
  shopName: string | null
  view: PauseView
  /** A pause or resume is in flight: Resume is disabled so a second tap cannot conflict (T-37-55). */
  pending: boolean
  onResume: () => void
}

/**
 * UI-SPEC K3 "Paused state": above the board, below the feed banner. White with a 2px slate-900
 * border, deliberately NOT slate-900-filled (L6: that fill is the customer allergy note's, and a cook
 * must never confuse the two). The sentence and its time are the SERVER's (`PauseView` is read from
 * the shop the server returned), never a client timer.
 */
export function PausedBanner({ shopName, view, pending, onResume }: PausedBannerProps) {
  if (view.kind === "none") return null
  const sentence =
    view.kind === "timed"
      ? `Orders paused until ${formatUkTime(view.until) ?? ""}. Customers can see your menu but can't order.`
      : "Orders paused until you reopen. Customers can see your menu but can't order."
  return (
    <div
      role="status"
      data-testid="kds-paused-banner"
      className="flex flex-wrap items-center gap-4 rounded-lg border-2 border-slate-900 bg-white p-4"
    >
      <PauseCircle className="h-6 w-6 shrink-0 text-slate-900" aria-hidden="true" />
      <div className="min-w-0 flex-1 basis-56 [overflow-wrap:anywhere]">
        {shopName && <p className="text-sm text-slate-600">{shopName}</p>}
        <p className="text-base font-semibold text-slate-900">{sentence}</p>
      </div>
      <Button className="kds-press h-11" onClick={onResume} disabled={pending}>
        Resume orders
      </Button>
    </div>
  )
}
