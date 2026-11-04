"use client"

import { useCallback, useEffect, useState } from "react"
import { Download } from "lucide-react"
import apiClient from "@/lib/api-client"
import { saveBlob } from "@/lib/finance-api"
import { formatUkDateTime } from "@/lib/uk-datetime"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { REJECT_REASONS } from "@/components/dashboard/orders/reject-orders-dialog"
import type { OrderTimelineEvent } from "@/types/api"

/**
 * The order's activity timeline (issue #857): who took each step, and when.
 *
 * Read from GET /api/v1/orders/{id}/timeline, which is the order's own audit history, so the page and
 * the audit trail cannot disagree. Client component because it loads on its own after the page: a
 * failed or slow history must never blank the order itself, so it has its own loading, empty and error
 * states (with Retry), and re-reads when `refreshKey` changes (the page passes the order's status and
 * update time, so a step taken on another screen shows up here too).
 *
 * "Download CSV" is made in the browser from what is on screen - same rows, nothing extra - with
 * spreadsheet formulas in a name defused (a leading = + - @ is read as a formula by Excel and Sheets).
 */

const STATUS_LABEL: Record<string, string> = {
  DRAFT: "Draft",
  PENDING: "Submitted",
  CONFIRMED: "Accepted",
  PREPARING: "Started preparing",
  READY: "Marked ready",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
  REFUNDED: "Refunded",
}

const PAYMENT_LABEL: Record<string, string> = {
  NONE: "No payment taken",
  PENDING: "Payment pending",
  AUTHORIZED: "Payment authorised",
  CAPTURED: "Payment captured",
  FAILED: "Payment failed",
  REFUNDED: "Payment refunded",
}

const CANCEL_REASON_LABEL: Record<string, string> = Object.fromEntries(
  REJECT_REASONS.map((r) => [r.code, r.label])
)

export const SYSTEM_ACTOR_COPY = "Customer or automatic"
export const UNNAMED_STAFF_COPY = "A team member"

export function eventLabel(e: OrderTimelineEvent): string {
  switch (e.kind) {
    case "PLACED":
      return "Order placed"
    case "PAYMENT":
      return PAYMENT_LABEL[e.paymentStatus ?? ""] ?? "Payment changed"
    case "VOIDED":
      return "Voided"
    default:
      return STATUS_LABEL[e.status ?? ""] ?? e.status ?? "Status changed"
  }
}

export function actorLabel(e: OrderTimelineEvent): string {
  if (e.actorType === "SYSTEM") return SYSTEM_ACTOR_COPY
  return e.actorName || UNNAMED_STAFF_COPY
}

/** The recorded reason in words: a cancel's code by its label, a void's reason as written. */
export function detailLabel(e: OrderTimelineEvent): string {
  if (!e.detail) return ""
  return CANCEL_REASON_LABEL[e.detail] ?? e.detail
}

/** RFC 4180 cell, with a spreadsheet formula defused first. */
function csvCell(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) ? `'${value}` : value
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
}

/** The timeline as CSV: time (ISO, UTC), event, status, by, detail. CRLF-terminated rows. */
export function timelineCsv(events: ReadonlyArray<OrderTimelineEvent>): string {
  const rows = [["time", "event", "status", "by", "detail"].join(",")]
  for (const e of events) {
    rows.push(
      [
        csvCell(e.at),
        csvCell(eventLabel(e)),
        csvCell(e.status ?? ""),
        csvCell(actorLabel(e)),
        csvCell(detailLabel(e)),
      ].join(",")
    )
  }
  return rows.join("\r\n") + "\r\n"
}

export interface OrderTimelineProps {
  orderId: string
  /** For the CSV's file name. */
  orderNumber?: string | null
  /** Changes when the order does, so the history is read again. */
  refreshKey: string
}

export function OrderTimeline({ orderId, orderNumber, refreshKey }: OrderTimelineProps) {
  const [events, setEvents] = useState<OrderTimelineEvent[] | null>(null)
  const [failed, setFailed] = useState(false)

  const load = useCallback(async () => {
    try {
      const res = await apiClient.get<OrderTimelineEvent[]>(`/api/v1/orders/${orderId}/timeline`)
      setEvents(Array.isArray(res.data) ? res.data : [])
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [orderId])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch-on-change effect; the load is async and its setState runs after the await, never synchronously in the effect body
    void load()
    // refreshKey is a re-read trigger, not a value the effect reads.
  }, [load, refreshKey])

  const download = () => {
    if (!events) return
    saveBlob(
      new Blob([timelineCsv(events)], { type: "text/csv;charset=utf-8" }),
      `order-${orderNumber || orderId.substring(0, 8)}-activity.csv`
    )
  }

  return (
    <Card data-testid="order-timeline">
      <CardHeader className="flex flex-col gap-3 space-y-0 sm:flex-row sm:items-start sm:justify-between">
        <div className="space-y-1.5">
          <CardTitle>Activity</CardTitle>
          <CardDescription>Who took each step, and when (UK time).</CardDescription>
        </div>
        <Button
          type="button"
          variant="outline"
          className="h-11"
          onClick={download}
          disabled={!events || events.length === 0}
        >
          <Download className="mr-2 h-4 w-4" aria-hidden="true" />
          Download CSV
        </Button>
      </CardHeader>
      <CardContent>
        {failed ? (
          <div role="alert" className="flex flex-wrap items-center gap-3 text-sm text-red-700">
            <span>Couldn&apos;t load the activity for this order.</span>
            <Button type="button" variant="outline" className="h-11" onClick={() => void load()}>
              Retry
            </Button>
          </div>
        ) : events === null ? (
          <p className="text-sm text-slate-600" role="status">
            Loading activity…
          </p>
        ) : events.length === 0 ? (
          <p className="text-sm text-slate-600">No activity recorded for this order.</p>
        ) : (
          <ol aria-label="Order activity" className="space-y-3">
            {events.map((e, i) => (
              <li
                key={`${e.at}-${i}`}
                className="flex flex-col gap-0.5 border-l-2 border-slate-200 pl-4 sm:flex-row sm:items-baseline sm:gap-3"
              >
                <time dateTime={e.at} className="text-sm tabular-nums text-slate-700 sm:w-56 sm:flex-shrink-0">
                  {formatUkDateTime(e.at)}
                </time>
                <span className="text-sm [overflow-wrap:anywhere]">
                  <span className="font-medium text-slate-900">{eventLabel(e)}</span>
                  <span className="text-slate-600"> · {actorLabel(e)}</span>
                  {detailLabel(e) && <span className="text-slate-600"> · {detailLabel(e)}</span>}
                </span>
              </li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  )
}

export default OrderTimeline
