"use client"

import Link from "next/link"
import { Clock } from "lucide-react"
import { Button } from "@/components/ui/button"
import { unreadableDays } from "@/lib/opening-schedule"
import type { Shop } from "@/types/api"

/**
 * 37-21 (UI-SPEC K4 "Vendor notices", D-25, RWO-022): the dashboard-home notice for shops whose hours
 * the vendor has not set or the platform could not read.
 *
 * It reads `hoursSource` from the shops list and NEVER re-parses the free text: after a vendor saves
 * structured hours the old text stays on the row and means nothing, and a legacy shop is legacy because
 * the server says so. (The days a NEEDS_REVIEW card names come from `unreadableDays`, the server's own
 * rule, which is itself gated on `hoursSource`.)
 *
 * Loading (UI-SPEC open assumption): `shops` is null until the list resolves and after a failed load;
 * either way nothing renders. The shops page badges still show the state.
 */

/** Where a notice's button goes: that shop's dialog, opened at the hours fieldset. */
export function shopHoursHref(shopId: string): string {
  return `/dashboard/shops?edit=${encodeURIComponent(shopId)}&focus=hours`
}

/** "Tuesday", "Tuesday and Thursday", "Monday, Tuesday and Friday". */
function listOfDays(days: string[]): string {
  if (days.length <= 1) return days.join("")
  return `${days.slice(0, -1).join(", ")} and ${days[days.length - 1]}`
}

/**
 * The needs-review sentence under the D-25 ruling: the unreadable days stay OPEN. The wording is the
 * owner's (ruling 2026-10-10, quick 261010-ppb, closes WINDOWS #36), singular for one day and plural
 * for several. The zero-day fallback is unchanged by the ruling.
 */
export function needsReviewSentence(shop: Shop): string {
  const days = unreadableDays(shop).map((d) => d.label)
  if (days.length === 0) {
    return "We couldn't read some of the hours you entered. Check them, then save your hours."
  }
  const those = days.length === 1 ? "that day" : "those days"
  return `We could not read the hours you entered for ${listOfDays(days)}, so customers can order on ${those} at any time. Check and save your hours.`
}

export function HoursNoticeCard({ shops }: { shops: Shop[] | null }) {
  if (!shops) return null
  const legacy = shops.filter((s) => s.hoursSource === "LEGACY_ALWAYS_OPEN")
  const review = shops.filter((s) => s.hoursSource === "NEEDS_REVIEW")
  if (legacy.length === 0 && review.length === 0) return null

  return (
    <section
      data-testid="hours-notice-card"
      aria-label="Opening hours to set"
      className="space-y-4 rounded-lg border border-amber-300 bg-white p-4"
    >
      {legacy.length > 0 && (
        <div className="space-y-3">
          <div className="flex items-start gap-3">
            <Clock className="mt-0.5 h-5 w-5 shrink-0 text-amber-800" aria-hidden="true" />
            <div className="min-w-0 [overflow-wrap:anywhere]">
              <p className="text-base font-semibold text-slate-900">
                {legacy.length === 1 ? "1 shop has no opening hours" : `${legacy.length} shops have no opening hours`}
              </p>
              <p className="text-sm text-slate-700">
                {`Customers can order from ${legacy.length === 1 ? "it" : "them"} at any time, including when you're closed.`}
              </p>
            </div>
          </div>
          <ul className="space-y-2">
            {legacy.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center justify-between gap-2 pl-8">
                <span className="min-w-0 text-sm font-medium text-slate-900 [overflow-wrap:anywhere]">{s.name}</span>
                <Button asChild className="h-11">
                  <Link href={shopHoursHref(s.id)} aria-label={`Set opening hours for ${s.name}`}>
                    Set opening hours
                  </Link>
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {review.map((s) => (
        <div key={s.id} className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex min-w-0 flex-1 items-start gap-3 basis-64">
            <Clock className="mt-0.5 h-5 w-5 shrink-0 text-amber-800" aria-hidden="true" />
            <div className="min-w-0 [overflow-wrap:anywhere]">
              <p className="text-base font-semibold text-slate-900">{`Check the opening hours for ${s.name}`}</p>
              <p className="text-sm text-slate-700">{needsReviewSentence(s)}</p>
            </div>
          </div>
          <Button asChild className="ml-8 h-11 sm:ml-0">
            <Link href={shopHoursHref(s.id)} aria-label={`Check hours for ${s.name}`}>
              Check hours
            </Link>
          </Button>
        </div>
      ))}
    </section>
  )
}
