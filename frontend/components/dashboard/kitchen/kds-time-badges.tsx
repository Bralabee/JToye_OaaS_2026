import { AlarmClock, Clock } from "lucide-react"
import { READY_BY_ESTIMATE_NOTE, readyByLabel } from "@/lib/ready-by"

/**
 * The estimated ready-by time on a kitchen ticket (#825, owner ruling 2026-11-04). Labelled "Est." so it
 * can never be read as a promise; renders nothing when the shop set no preparation time (the server then
 * omits the field). Words and an icon carry it, never colour alone (the board's L7 rule).
 */
export function KdsReadyByBadge({ estimatedReadyAt }: { estimatedReadyAt?: string | null }) {
  const label = readyByLabel(estimatedReadyAt)
  if (label === null) return null
  return (
    <span
      data-testid="kds-ready-by-badge"
      title={READY_BY_ESTIMATE_NOTE}
      className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-md border border-slate-400 bg-white px-2 py-0.5 text-sm font-medium text-slate-900"
    >
      <Clock aria-hidden="true" className="h-4 w-4" />
      Est. ready {label}
    </span>
  )
}

/**
 * The "waiting too long" alert on a New-lane ticket (#821, owner ruling 2026-11-04): the order has sat
 * PENDING past the platform's threshold, the sweep stamped `unacceptedNotifiedAt` and told the customer
 * the shop has not yet accepted. Shown only while the order is still PENDING; accepting it ends it.
 */
export function KdsUnacceptedBadge({
  status,
  unacceptedNotifiedAt,
}: {
  status?: string | null
  unacceptedNotifiedAt?: string | null
}) {
  if (status !== "PENDING" || !unacceptedNotifiedAt) return null
  return (
    <span
      data-testid="kds-unaccepted-badge"
      className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-md border-2 border-red-800 bg-red-50 px-2 py-0.5 text-sm font-semibold text-red-900"
    >
      <AlarmClock aria-hidden="true" className="h-4 w-4" />
      Waiting too long. The customer has been told.
    </span>
  )
}
