"use client"

import { forwardRef, useImperativeHandle, useMemo, useRef, useState } from "react"
import { Trash2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { IconButton } from "@/components/ui/icon-button"
import { Input } from "@/components/ui/input"
import { DAY_LABELS, DAY_ORDER } from "@/lib/opening-hours"
import {
  MAX_WINDOWS_PER_DAY,
  MINUTES_IN_DAY,
  isOvernight,
  spansOverlap,
  unreadableDays,
  windowSpan,
  type DayKey,
  type OpeningScheduleMap,
} from "@/lib/opening-schedule"
import type { Shop } from "@/types/api"

/**
 * 37-21 (UI-SPEC K4, D-15, D-25, RWO-022): the structured opening-hours editor that replaces the
 * shop dialog's seven free-text inputs.
 *
 * What it saves is what the server enforces (37-20's `CreateShopRequest.openingSchedule`): any save
 * makes the hours STRUCTURED and ends a legacy shop's "open at any time"; `{}` is closed every day;
 * an untouched editor sends nothing, so the stored hours (and their source) are kept. The editor
 * validates what the server would refuse (a missing time, overlapping windows, an overnight window
 * running into the next day's hours, more than 3 windows a day) before the request goes out; the
 * server stays the authority, and its 400 reaches the dialog's own error path.
 */

/** UI-SPEC § Copywriting K3/K4. */
export const HOURS_COPY = {
  overlap: "These times overlap. Change one of them.",
  missing: "Add a closing time, or tick Closed.",
  overnight: "Closes after midnight (next day)",
  allClosed: "With no opening hours, customers can't order from this shop.",
  legacy:
    "Customers can currently order from this shop at any time, because no opening hours were set. Add your hours to change that.",
  /**
   * Owner ruling 2026-10-10 (D-25): the server keeps an unreadable NEEDS_REVIEW day OPEN all day until
   * the vendor saves hours. The UI-SPEC's earlier "This day shows as closed." described the old client
   * render, not what customers get. Orchestrator copy decision, pending the owner's own wording.
   */
  unreadable: (text: string) =>
    `We couldn't read "${text}". Customers can still order on this day at any time until you save your hours.`,
}

const SHORT_DAY: Record<DayKey, string> = {
  mon: "Mon", tue: "Tue", wed: "Wed", thu: "Thu", fri: "Fri", sat: "Sat", sun: "Sun",
}

interface EditorWindow {
  key: number
  open: string
  close: string
}

interface EditorDay {
  closed: boolean
  windows: EditorWindow[]
}

type EditorDays = Record<DayKey, EditorDay>

interface DayError {
  message: string
  /** Input ids to mark aria-invalid, in focus order. */
  invalid: string[]
}

type Errors = Partial<Record<DayKey, DayError>>

const inputId = (day: DayKey, index: number, edge: "open" | "close") => `hours-${day}-${index}-${edge}`
const errorId = (day: DayKey) => `hours-${day}-error`

function inputLabel(day: DayKey, index: number, edge: "open" | "close"): string {
  const base = `${DAY_LABELS[day]} ${edge === "open" ? "opens" : "closes"}`
  return index === 0 ? base : `${base}, time ${index + 1}`
}

type ScheduleShop = Pick<Shop, "hoursSource" | "openingSchedule" | "legacyOpeningHours">

/** The editor's starting state: a legacy shop (and a new one) has no hours; others start from the schedule. */
function initialDays(shop: ScheduleShop | null, nextKey: () => number): EditorDays {
  const schedule =
    shop && shop.hoursSource !== "LEGACY_ALWAYS_OPEN" ? ((shop.openingSchedule ?? {}) as OpeningScheduleMap) : {}
  const days = {} as EditorDays
  for (const day of DAY_ORDER) {
    const windows = (schedule[day] ?? []).slice(0, MAX_WINDOWS_PER_DAY)
    days[day] = {
      closed: windows.length === 0,
      windows: windows.map((w) => ({ key: nextKey(), open: w.open, close: w.close })),
    }
  }
  return days
}

/** The request shape: only days with windows; `{}` when every day is closed. */
export function toRequestSchedule(days: EditorDays): OpeningScheduleMap {
  const out: OpeningScheduleMap = {}
  for (const day of DAY_ORDER) {
    const d = days[day]
    if (!d.closed && d.windows.length > 0) out[day] = d.windows.map(({ open, close }) => ({ open, close }))
  }
  return out
}

/** What the server would refuse, per day, as the copy and the inputs to mark. */
function validateDays(days: EditorDays): Errors {
  const errors: Errors = {}
  DAY_ORDER.forEach((day, dayIndex) => {
    const d = days[day]
    if (d.closed) return
    const missing: string[] = []
    d.windows.forEach((w, i) => {
      if (!w.open) missing.push(inputId(day, i, "open"))
      if (!w.close) missing.push(inputId(day, i, "close"))
    })
    if (missing.length > 0) {
      errors[day] = { message: HOURS_COPY.missing, invalid: missing }
      return
    }
    const both = (i: number, on: DayKey = day) => [inputId(on, i, "open"), inputId(on, i, "close")]
    const spans = d.windows.map((w) => windowSpan(w))
    for (let i = 0; i < spans.length; i++) {
      for (let j = i + 1; j < spans.length; j++) {
        const a = spans[i]
        const b = spans[j]
        if (a && b && spansOverlap(a, b)) {
          errors[day] = { message: HOURS_COPY.overlap, invalid: [...both(i), ...both(j)] }
          return
        }
      }
    }
    // An overnight window that runs into the next day's first hours (Sunday night runs into Monday).
    const next = DAY_ORDER[(dayIndex + 1) % DAY_ORDER.length]
    if (days[next].closed) return
    for (let i = 0; i < spans.length; i++) {
      const span = spans[i]
      const spill = span ? span.end - MINUTES_IN_DAY : 0
      if (spill <= 0) continue
      for (let j = 0; j < days[next].windows.length; j++) {
        const t = windowSpan(days[next].windows[j])
        if (t && t.start < spill) {
          errors[day] = { message: HOURS_COPY.overlap, invalid: [...both(i), ...both(j, next)] }
          return
        }
      }
    }
  })
  return errors
}

export interface OpeningScheduleEditorHandle {
  /** Validate every day, show the errors, and focus the first invalid input. True when valid. */
  validate: () => boolean
}

interface OpeningScheduleEditorProps {
  /** The shop being edited, or null when creating one. */
  shop: ScheduleShop | null
  /** Every edit reports the request shape; the dialog sends `openingSchedule` only once this fired. */
  onChange: (schedule: OpeningScheduleMap) => void
}

export const OpeningScheduleEditor = forwardRef<OpeningScheduleEditorHandle, OpeningScheduleEditorProps>(
  function OpeningScheduleEditor({ shop, onChange }, ref) {
    const keyRef = useRef(0)
    const nextKey = () => ++keyRef.current
    const [days, setDays] = useState<EditorDays>(() => initialDays(shop, () => ++keyRef.current))
    const [touched, setTouched] = useState(false)
    const [errors, setErrors] = useState<Errors>({})
    const unreadable = useMemo(() => new Map(unreadableDays(shop ?? {}).map((u) => [u.day, u.text])), [shop])
    const legacy = shop?.hoursSource === "LEGACY_ALWAYS_OPEN"
    const needsReview = shop?.hoursSource === "NEEDS_REVIEW"

    const update = (change: (prev: EditorDays) => EditorDays, clear: DayKey[]) => {
      const next = change(days)
      setDays(next)
      setTouched(true)
      setErrors((prev) => {
        const copy = { ...prev }
        for (const day of clear) delete copy[day]
        return copy
      })
      onChange(toRequestSchedule(next))
    }

    const setDay = (day: DayKey, value: EditorDay) => update((prev) => ({ ...prev, [day]: value }), [day])

    const focusFirst = (found: Errors) => {
      for (const day of DAY_ORDER) {
        const first = found[day]?.invalid[0]
        if (first) {
          document.getElementById(first)?.focus()
          return
        }
      }
    }

    useImperativeHandle(ref, () => ({
      validate: () => {
        const found = validateDays(days)
        setErrors(found)
        if (Object.keys(found).length === 0) return true
        focusFirst(found)
        return false
      },
    }))

    const validateDayOnBlur = (day: DayKey) => {
      const found = validateDays(days)
      setErrors((prev) => {
        const copy = { ...prev }
        if (found[day]) copy[day] = found[day]
        else delete copy[day]
        return copy
      })
    }

    const copyMonday = () => {
      const mon = days.mon
      update((prev) => {
        const next = { ...prev }
        for (const day of DAY_ORDER) {
          if (day === "mon") continue
          next[day] = { closed: mon.closed, windows: mon.windows.map((w) => ({ ...w, key: nextKey() })) }
        }
        return next
      }, [...DAY_ORDER])
    }

    const allClosed = DAY_ORDER.every((day) => days[day].closed)
    // Untouched, a legacy or needs-review shop still takes orders the way it did (D-25), so the
    // "no opening hours" note would be false until something is changed.
    const showAllClosedNote = allClosed && (touched || (!legacy && !needsReview))
    const invalid = new Set(Object.values(errors).flatMap((e) => e?.invalid ?? []))
    const errorFor = new Map<string, DayKey>()
    for (const day of DAY_ORDER) for (const id of errors[day]?.invalid ?? []) if (!errorFor.has(id)) errorFor.set(id, day)

    return (
      <fieldset id="opening-hours" tabIndex={-1} className="space-y-3 focus:outline-none">
        <legend className="mb-1 text-sm font-semibold text-slate-900">Opening hours</legend>
        {legacy && <p className="text-sm text-slate-700">{HOURS_COPY.legacy}</p>}
        <Button type="button" variant="link" className="h-11 px-0" onClick={copyMonday}>
          Copy Monday to every day
        </Button>
        <div className="divide-y divide-slate-100">
          {DAY_ORDER.map((day) => {
            const d = days[day]
            const label = DAY_LABELS[day]
            const error = errors[day]
            const quote = unreadable.get(day)
            return (
              <div key={day} role="group" aria-label={label} className="space-y-2 py-3">
                <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:gap-4">
                  <div className="flex items-center gap-4 sm:w-36 sm:shrink-0">
                    <span aria-hidden="true" className="w-12 text-sm font-semibold text-slate-900">
                      {SHORT_DAY[day]}
                    </span>
                    <label htmlFor={`hours-${day}-closed`} className="flex min-h-11 cursor-pointer items-center gap-2 text-sm text-slate-700">
                      <Checkbox
                        id={`hours-${day}-closed`}
                        checked={d.closed}
                        onCheckedChange={(checked) =>
                          setDay(day, checked === true
                            ? { closed: true, windows: [] }
                            : { closed: false, windows: [{ key: nextKey(), open: "", close: "" }] })
                        }
                      />
                      Closed<span className="sr-only"> on {label}</span>
                    </label>
                  </div>
                  {!d.closed && (
                    <div className="min-w-0 flex-1 space-y-2">
                      {d.windows.map((w, i) => {
                        const openId = inputId(day, i, "open")
                        const closeId = inputId(day, i, "close")
                        const described = (id: string) => (invalid.has(id) && errorFor.get(id) ? errorId(errorFor.get(id) as DayKey) : undefined)
                        const setWindow = (patch: Partial<EditorWindow>) =>
                          setDay(day, { closed: false, windows: d.windows.map((x, j) => (j === i ? { ...x, ...patch } : x)) })
                        return (
                          <div key={w.key} className="space-y-1">
                            <div className="flex items-center gap-2">
                              <Input
                                id={openId}
                                type="time"
                                step={900}
                                aria-label={inputLabel(day, i, "open")}
                                aria-invalid={invalid.has(openId) || undefined}
                                aria-describedby={described(openId)}
                                value={w.open}
                                onChange={(e) => setWindow({ open: e.target.value })}
                                onBlur={() => validateDayOnBlur(day)}
                                className="h-11 min-w-0 flex-1 basis-0"
                              />
                              <Input
                                id={closeId}
                                type="time"
                                step={900}
                                aria-label={inputLabel(day, i, "close")}
                                aria-invalid={invalid.has(closeId) || undefined}
                                aria-describedby={described(closeId)}
                                value={w.close}
                                onChange={(e) => setWindow({ close: e.target.value })}
                                onBlur={() => validateDayOnBlur(day)}
                                className="h-11 min-w-0 flex-1 basis-0"
                              />
                              <IconButton
                                type="button"
                                label={
                                  w.open && w.close
                                    ? `Remove ${label} ${w.open} to ${w.close}`
                                    : `Remove ${label} time ${i + 1}`
                                }
                                icon={<Trash2 className="h-4 w-4" />}
                                className="h-11 w-11 shrink-0 text-slate-600"
                                onClick={() => {
                                  const windows = d.windows.filter((_, j) => j !== i)
                                  setDay(day, { closed: windows.length === 0, windows })
                                }}
                              />
                            </div>
                            {w.open && w.close && isOvernight(w) && (
                              <p className="text-sm text-slate-600">{HOURS_COPY.overnight}</p>
                            )}
                          </div>
                        )
                      })}
                      {d.windows.length < MAX_WINDOWS_PER_DAY && (
                        <Button
                          type="button"
                          variant="link"
                          className="h-11 px-0"
                          onClick={() =>
                            setDay(day, { closed: false, windows: [...d.windows, { key: nextKey(), open: "", close: "" }] })
                          }
                        >
                          Add another time
                        </Button>
                      )}
                    </div>
                  )}
                </div>
                {error && (
                  <p id={errorId(day)} role="alert" className="text-sm font-medium text-red-700">
                    {error.message}
                  </p>
                )}
                {quote !== undefined && (
                  <p className="text-sm text-amber-800 [overflow-wrap:anywhere]">{HOURS_COPY.unreadable(quote)}</p>
                )}
              </div>
            )
          })}
        </div>
        {showAllClosedNote && <p className="text-sm text-amber-800">{HOURS_COPY.allClosed}</p>}
      </fieldset>
    )
  }
)
