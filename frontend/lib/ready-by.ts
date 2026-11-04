import { formatUkClockTime } from "@/lib/uk-datetime"

/**
 * The ESTIMATED ready-by time (#825, owner ruling 2026-11-04, persona close-out).
 *
 * The server stores `estimatedReadyAt` = (placed-at or accepted-at) + the shop's default preparation
 * time, and omits it when the shop has set no preparation time (or the order is a scheduled-slot order,
 * which carries its own window). Every surface that shows it must say it is an ESTIMATE, never a
 * promise, and must show NOTHING when the field is absent: no guessed time, no "unknown".
 */
export const READY_BY_ESTIMATE_NOTE = "This is an estimate, not a promise."

/**
 * "14:35", or "14:35 on 4 Nov" when it is not today in London. `null` when the server sent no estimate
 * (absent, null or unparseable), so callers render nothing.
 */
export function readyByLabel(estimatedReadyAt: string | null | undefined, now: Date = new Date()): string | null {
  if (!estimatedReadyAt) return null
  const label = formatUkClockTime(estimatedReadyAt, now)
  return label === "" ? null : label
}

/**
 * Statuses in which a ready-by time still means something to the customer. Once the order is READY the
 * time has passed its purpose, and a cancelled / refunded / unpaid order will not be made.
 */
const ESTIMATE_RELEVANT_STATUSES = ["PENDING", "CONFIRMED", "PREPARING"]

export function readyByIsRelevant(status: string | null | undefined): boolean {
  return status != null && ESTIMATE_RELEVANT_STATUSES.includes(status)
}
