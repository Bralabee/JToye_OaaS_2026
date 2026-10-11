import { DAY_LABELS, DAY_ORDER } from "@/lib/opening-hours"
import type { HoursSource, OpeningWindow } from "@/types/storefront"

/**
 * Structured opening hours on the client (37-21; UI-SPEC K4; D-15, D-25).
 *
 * The SERVER decides whether a shop takes an order (37-20's `ShopAvailabilityPolicy`). Nothing here
 * decides that. This module only:
 *  - words a schedule the server sent, for the storefront hours list and the editor
 *    ("09:00–17:00", an overnight window marked, "Closed");
 *  - names the NEEDS_REVIEW days the server keeps open because V80 could not read them, by the
 *    server's OWN rule (`ShopAvailabilityPolicy.withUnreadableDaysOpen`), gated on `hoursSource`;
 *  - builds the request shape the shop update takes (`CreateShopRequest.openingSchedule`).
 *
 * The wire shape is `{ mon: [{ open: "HH:MM", close: "HH:MM" }], ... }` in UK local time. A day that
 * is absent or `[]` is closed. A window whose close is at or before its open closes the next day.
 */

export type DayKey = (typeof DAY_ORDER)[number]

/** The request and response shape: weekday key -> windows. `{}` = closed every day. */
export type OpeningScheduleMap = Partial<Record<DayKey, OpeningWindow[]>>

export const MAX_WINDOWS_PER_DAY = 3

const MINUTES_PER_DAY = 24 * 60

/** Minutes after midnight for "HH:MM", or null when the value is not a time. */
export function minutesOf(value: string | null | undefined): number | null {
  if (!value) return null
  const m = /^(\d{2}):(\d{2})$/.exec(value.trim())
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2])
  if (h > 23 || min > 59) return null
  return h * 60 + min
}

/** True when the window closes on the next day: its close is at or before its open (the server's rule). */
export function isOvernight(window: OpeningWindow): boolean {
  const open = minutesOf(window.open)
  const close = minutesOf(window.close)
  return open !== null && close !== null && close <= open
}

/**
 * One window as the storefront prints it: "09:00–17:00"; "18:00–02:00 (next day)" when it closes after
 * midnight; "Open 24 hours" for a window that closes at the minute it opens (00:00–00:00, the seeded
 * all-day window, which the server reads as 24 hours).
 */
export function formatWindow(window: OpeningWindow): string {
  if (window.open === window.close) return "Open 24 hours"
  const text = `${window.open}–${window.close}`
  return isOvernight(window) ? `${text} (next day)` : text
}

/** A whole day: "Closed" with no windows, otherwise the windows joined by commas. */
export function formatDay(windows: OpeningWindow[] | null | undefined): string {
  if (!windows || windows.length === 0) return "Closed"
  return windows.map(formatWindow).join(", ")
}

/** Monday first, every day present: the rows the storefront list renders from a schedule. */
export function scheduleRows(schedule: OpeningScheduleMap): { day: DayKey; label: string; text: string }[] {
  return DAY_ORDER.map((day) => ({ day, label: DAY_LABELS[day], text: formatDay(schedule[day]) }))
}

interface ScheduleSourceFields {
  hoursSource?: HoursSource | null
  openingSchedule?: OpeningScheduleMap | Record<string, OpeningWindow[]> | null
  /** The vendor's original free text (ShopDto.legacyOpeningHours). */
  legacyOpeningHours?: Record<string, string> | null
}

/**
 * The NEEDS_REVIEW days V80 could not read, with the vendor's original text, Monday first.
 *
 * Only for a shop whose `hoursSource` is NEEDS_REVIEW: any other source returns none, whatever its
 * text says (after a vendor saves hours the old text stays on the row, and it means nothing). Within
 * such a shop a day is unreadable exactly when the server keeps it open all day under the D-25 ruling
 * (2026-10-10): its schedule is empty and its text is present and is not the word "closed". This is
 * the server's rule, mirrored so the vendor sees the same days the server treats as open; it never
 * reads a time out of the text.
 */
export function unreadableDays(shop: ScheduleSourceFields): { day: DayKey; label: string; text: string }[] {
  if (shop.hoursSource !== "NEEDS_REVIEW") return []
  const schedule = (shop.openingSchedule ?? {}) as OpeningScheduleMap
  const text = shop.legacyOpeningHours ?? {}
  const days: { day: DayKey; label: string; text: string }[] = []
  for (const day of DAY_ORDER) {
    const entry = text[day]
    const windows = schedule[day] ?? []
    if (windows.length === 0 && typeof entry === "string" && entry.trim().toLowerCase() !== "closed") {
      days.push({ day, label: DAY_LABELS[day], text: entry })
    }
  }
  return days
}

/** Overlap and next-day spill checks, as the server's `OpeningSchedule.validationErrors` applies them. */
export function windowSpan(window: OpeningWindow): { start: number; end: number } | null {
  const start = minutesOf(window.open)
  const close = minutesOf(window.close)
  if (start === null || close === null) return null
  return { start, end: close <= start ? close + MINUTES_PER_DAY : close }
}

export function spansOverlap(a: { start: number; end: number }, b: { start: number; end: number }): boolean {
  return a.start < b.end && b.start < a.end
}

export const MINUTES_IN_DAY = MINUTES_PER_DAY
