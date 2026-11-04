import { Clock } from "lucide-react"
import { READY_BY_ESTIMATE_NOTE, readyByIsRelevant, readyByLabel } from "@/lib/ready-by"

/**
 * The customer's "estimated ready by" line on the order confirmation and the tracking pages (#825,
 * owner ruling 2026-11-04). Always labelled as an estimate. Renders NOTHING when the shop gave no
 * preparation time (the server then omits the field), when the order is no longer waiting to be made,
 * or when the value cannot be read: never a guessed time.
 */
export function EstimatedReadyBy({
  estimatedReadyAt,
  status,
  className = "",
}: {
  estimatedReadyAt?: string | null
  status?: string | null
  className?: string
}) {
  if (!readyByIsRelevant(status)) return null
  const label = readyByLabel(estimatedReadyAt)
  if (label === null) return null
  return (
    <p
      data-testid="estimated-ready-by"
      className={`flex flex-wrap items-center justify-center gap-x-2 gap-y-0.5 text-sm text-slate-800 ${className}`}
    >
      <Clock aria-hidden="true" className="h-4 w-4 flex-shrink-0 text-slate-600" />
      <span>
        Estimated ready by <strong className="font-semibold">{label}</strong>
      </span>
      <span className="text-slate-600">{READY_BY_ESTIMATE_NOTE}</span>
    </p>
  )
}
