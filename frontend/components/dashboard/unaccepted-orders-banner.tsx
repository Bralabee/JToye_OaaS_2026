import Link from "next/link"
import { AlarmClock } from "lucide-react"
import type { Order } from "@/types/api"

/**
 * Dashboard alert for orders that have waited too long for the shop to accept them (#821, owner ruling
 * 2026-11-04). Driven by the server's `unacceptedNotifiedAt` stamp (the unaccepted-order sweep), so the
 * threshold lives in one place, the backend config. Counts only orders that are STILL pending; accepting
 * one clears it. Renders nothing when there are none.
 *
 * Words and an icon, not colour alone; a named link to the kitchen board, where the orders are accepted.
 */
export function overdueUnacceptedCount(orders: Pick<Order, "status" | "unacceptedNotifiedAt">[]): number {
  return orders.filter((o) => o.status === "PENDING" && !!o.unacceptedNotifiedAt).length
}

export function UnacceptedOrdersBanner({ orders }: { orders: Pick<Order, "status" | "unacceptedNotifiedAt">[] }) {
  const count = overdueUnacceptedCount(orders)
  if (count === 0) return null
  return (
    <div
      role="status"
      data-testid="unaccepted-orders-banner"
      className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border-2 border-red-800 bg-red-50 p-4 text-sm text-red-900"
    >
      <AlarmClock aria-hidden="true" className="h-5 w-5 flex-shrink-0" />
      <span className="font-semibold">
        {count === 1
          ? "1 order has been waiting too long for you to accept it."
          : `${count} orders have been waiting too long for you to accept them.`}
      </span>
      <span>The customers have been told the shop has not yet accepted.</span>
      <Link href="/dashboard/kitchen" className="font-semibold underline">
        Open the kitchen board
      </Link>
    </div>
  )
}
