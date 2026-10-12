"use client"

import { useId, useState } from "react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import apiClient from "@/lib/api-client"

/**
 * RejectOrdersDialog — 37-30 (UI-SPEC § D1 "RejectOrdersDialog"; D-18, SC-4; RWO-041).
 *
 * The one reject dialog for the kitchen's New lane (one order, or a selection) and the Orders
 * page (a selection of PENDING rows). It is the ConfirmActionDialog shape — title, body,
 * "Keep order(s)" / destructive confirm, "Working…" while pending, no closing mid-flight — plus
 * the two things that pattern has no slot for:
 *
 *   - a REQUIRED reason, kept on the order (`orders.cancel_reason_code`, V81) and never sent to
 *     the customer (37-29 proves that on the email, the tracking payload, the webhook envelope
 *     and the order-state event; the body copy says so and is true);
 *   - a per-order RESULT SUMMARY after the submit, with a "Close summary" button, because a batch
 *     can be partly refused and the person must read which orders were not rejected before the
 *     dialog goes away. It does not auto-close.
 *
 * ONE ENDPOINT FOR ONE OR MANY. Every submit is 37-29's POST /api/v1/orders/bulk-cancel
 * {orderIds, reasonCode} -> {results: [{orderId, outcome}]}, a batch of one included. The
 * single cancel (POST /orders/{id}/cancel, optional {reasonCode}) would cancel a CONFIRMED order
 * too, so a kitchen tapping Reject on a card a colleague accepted a second earlier would cancel
 * an accepted order; the bulk path classifies on the committed state and reports it
 * ALREADY_ACCEPTED instead, which is the race UI-SPEC D1's result copy exists for. Idempotent by
 * construction, so no Idempotency-Key.
 *
 * The server decides: STAFF on each order's own shop (an ungranted or unknown id is NOT_FOUND,
 * non-disclosing), the request-size bound (400 bulk-cancel-too-large), the scope (403).
 */

export interface RejectableOrder {
  id: string
  /** The order number the title names ("Reject order ORD-…?"). */
  label: string
}

/** 37-29's outcome per id, in request order. */
export type RejectOutcome = "REJECTED" | "ALREADY_ACCEPTED" | "ALREADY_CANCELLED" | "NOT_FOUND"

export type RejectReasonCode = "SUSPECTED_FAKE" | "CANNOT_FULFIL" | "CUSTOMER_REQUEST" | "OTHER"

/** The four default reasons (UI-SPEC § Copywriting D1), in the order the Select shows them. */
export const REJECT_REASONS: ReadonlyArray<{ code: RejectReasonCode; label: string }> = [
  { code: "SUSPECTED_FAKE", label: "Looks like a fake or test order" },
  { code: "CANNOT_FULFIL", label: "We can't make this right now" },
  { code: "CUSTOMER_REQUEST", label: "The customer asked to cancel" },
  { code: "OTHER", label: "Something else" },
]

export const REJECT_REASON_LABEL = "Reason (kept on the order)"
export const REJECT_REASON_PLACEHOLDER = "Choose a reason"
export const KEEP_ORDER_COPY = "Keep order"
export const KEEP_ORDERS_COPY = "Keep orders"
export const CLOSE_SUMMARY_COPY = "Close summary"
export const REJECT_FAILED_COPY =
  "Couldn't reject. Check your connection and try again. Nothing was changed."
export const REJECT_REFUSED_COPY = "Couldn't reject these orders. Nothing was changed. Try again."
export const REJECT_FORBIDDEN_COPY = "You don't have access to reject these orders. Nothing was changed."

const BULK_CANCEL_ENDPOINT = "/api/v1/orders/bulk-cancel"
const TOO_LARGE_TYPE_SUFFIX = "/bulk-cancel-too-large"

export function rejectTitleCopy(orders: ReadonlyArray<RejectableOrder>): string {
  return orders.length === 1 ? `Reject order ${orders[0].label}?` : `Reject ${orders.length} orders?`
}

export function rejectConfirmCopy(count: number): string {
  return count === 1 ? "Reject order" : `Reject ${count} orders`
}

export function keepCopy(count: number): string {
  return count === 1 ? KEEP_ORDER_COPY : KEEP_ORDERS_COPY
}

/** UI-SPEC § Copywriting D1 "Body", with the one singular/plural variation it declares. */
export function rejectBodyCopy(count: number): string {
  const told = count === 1 ? "The customer is told" : "The customers are told"
  return (
    `${told} the shop couldn't accept the order. Any stock is put back. This can't be undone. ` +
    "The reason stays on the order for your records and isn't sent to the customer."
  )
}

export function rejectTooLargeCopy(limit: number): string {
  return `You can reject up to ${limit} orders at once. Select fewer and try again.`
}

export interface RejectCounts {
  rejected: number
  alreadyAccepted: number
  alreadyCancelled: number
  notFound: number
}

export function countOutcomes(results: ReadonlyArray<{ outcome: RejectOutcome }>): RejectCounts {
  const counts: RejectCounts = { rejected: 0, alreadyAccepted: 0, alreadyCancelled: 0, notFound: 0 }
  for (const r of results) {
    if (r.outcome === "REJECTED") counts.rejected += 1
    else if (r.outcome === "ALREADY_ACCEPTED") counts.alreadyAccepted += 1
    else if (r.outcome === "ALREADY_CANCELLED") counts.alreadyCancelled += 1
    else if (r.outcome === "NOT_FOUND") counts.notFound += 1
  }
  return counts
}

/**
 * UI-SPEC § Copywriting D1 "Result": "{n} rejected. {m} couldn't be rejected because they'd
 * already been accepted." with zero counts omitted. The two outcomes the spec does not word
 * (ALREADY_CANCELLED, NOT_FOUND) get a sentence in the same shape, zero omitted the same way,
 * so a batch is never summarised with orders unaccounted for.
 */
export function rejectResultCopy(counts: RejectCounts): string {
  const parts: string[] = []
  if (counts.rejected > 0) parts.push(`${counts.rejected} rejected.`)
  if (counts.alreadyAccepted > 0) {
    parts.push(
      counts.alreadyAccepted === 1
        ? "1 couldn't be rejected because it had already been accepted."
        : `${counts.alreadyAccepted} couldn't be rejected because they'd already been accepted.`
    )
  }
  if (counts.alreadyCancelled > 0) {
    parts.push(
      counts.alreadyCancelled === 1
        ? "1 had already been rejected."
        : `${counts.alreadyCancelled} had already been rejected.`
    )
  }
  if (counts.notFound > 0) {
    parts.push(counts.notFound === 1 ? "1 couldn't be found." : `${counts.notFound} couldn't be found.`)
  }
  return parts.length > 0 ? parts.join(" ") : "Nothing was rejected."
}

interface BulkCancelResponse {
  results?: Array<{ orderId: string; outcome: RejectOutcome }>
}

interface ProblemResponse {
  response?: { status?: number; data?: { type?: unknown; limit?: unknown } }
}

/** The copy for a failed submit, from what the server said (nothing was changed in every case). */
export function describeRejectFailure(error: unknown): string {
  const response = (error as ProblemResponse | null)?.response
  if (!response) return REJECT_FAILED_COPY
  const type = response.data?.type
  if (
    response.status === 400 &&
    typeof type === "string" &&
    type.endsWith(TOO_LARGE_TYPE_SUFFIX) &&
    typeof response.data?.limit === "number"
  ) {
    return rejectTooLargeCopy(response.data.limit)
  }
  if (response.status === 403) return REJECT_FORBIDDEN_COPY
  return REJECT_REFUSED_COPY
}

export interface RejectOrdersDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The orders to reject, in the order they were selected. */
  orders: ReadonlyArray<RejectableOrder>
  /** Called once per submit with exactly the ids the server reported REJECTED (may be empty). */
  onRejected: (orderIds: string[]) => void
  /** Called when the summary is dismissed after a submit — the caller clears its selection and re-reads. */
  onClosedAfterSubmit?: () => void
}

type Phase = "confirm" | "summary"

export function RejectOrdersDialog({
  open,
  onOpenChange,
  orders,
  onRejected,
  onClosedAfterSubmit,
}: RejectOrdersDialogProps) {
  const [reason, setReason] = useState<RejectReasonCode | "">("")
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const [phase, setPhase] = useState<Phase>("confirm")
  const [counts, setCounts] = useState<RejectCounts | null>(null)
  const reasonId = useId()
  const reasonLabelId = useId()
  const count = orders.length

  const reset = () => {
    setReason("")
    setFailure(null)
    setPhase("confirm")
    setCounts(null)
  }

  const finish = () => {
    reset()
    onOpenChange(false)
    onClosedAfterSubmit?.()
  }

  const handleOpenChange = (next: boolean) => {
    if (pending) return
    if (next) {
      onOpenChange(true)
      return
    }
    if (phase === "summary") {
      finish()
      return
    }
    reset()
    onOpenChange(false)
  }

  const handleConfirm = async () => {
    if (!reason || pending || count === 0) return
    setPending(true)
    setFailure(null)
    try {
      const res = await apiClient.post<BulkCancelResponse>(BULK_CANCEL_ENDPOINT, {
        orderIds: orders.map((o) => o.id),
        reasonCode: reason,
      })
      const results = Array.isArray(res?.data?.results) ? res.data.results : []
      setCounts(countOutcomes(results))
      setPhase("summary")
      onRejected(results.filter((r) => r.outcome === "REJECTED").map((r) => r.orderId))
    } catch (e: unknown) {
      setFailure(describeRejectFailure(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{rejectTitleCopy(orders)}</DialogTitle>
          <DialogDescription>{rejectBodyCopy(count)}</DialogDescription>
        </DialogHeader>

        {phase === "confirm" ? (
          <>
            <div className="space-y-2">
              <Label id={reasonLabelId} htmlFor={reasonId}>
                {REJECT_REASON_LABEL}
              </Label>
              <Select
                value={reason || undefined}
                onValueChange={(v) => setReason(v as RejectReasonCode)}
                disabled={pending}
              >
                <SelectTrigger
                  id={reasonId}
                  className="h-11"
                  aria-labelledby={reasonLabelId}
                  aria-required="true"
                >
                  <SelectValue placeholder={REJECT_REASON_PLACEHOLDER} />
                </SelectTrigger>
                <SelectContent>
                  {REJECT_REASONS.map((r) => (
                    <SelectItem key={r.code} value={r.code}>
                      {r.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {failure && (
              <p role="alert" className="text-sm font-medium text-red-700 [overflow-wrap:anywhere]">
                {failure}
              </p>
            )}

            <DialogFooter className="gap-2 sm:gap-2">
              <Button
                type="button"
                variant="outline"
                className="h-11"
                onClick={() => handleOpenChange(false)}
                disabled={pending}
              >
                {keepCopy(count)}
              </Button>
              <Button
                type="button"
                variant="destructive"
                className="h-11"
                onClick={handleConfirm}
                disabled={pending || !reason}
              >
                {pending ? "Working…" : rejectConfirmCopy(count)}
              </Button>
            </DialogFooter>
          </>
        ) : (
          <>
            <p
              role="status"
              data-testid="reject-summary"
              className="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 text-sm font-medium text-slate-900"
            >
              {counts ? rejectResultCopy(counts) : ""}
            </p>
            <DialogFooter>
              <Button type="button" className="h-11" onClick={finish}>
                {CLOSE_SUMMARY_COPY}
              </Button>
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

export default RejectOrdersDialog
