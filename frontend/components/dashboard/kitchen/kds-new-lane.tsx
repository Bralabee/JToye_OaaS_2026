"use client"

import { useRef, useState } from "react"
import { m, AnimatePresence } from "framer-motion"
import { BellRing, Check, Clock, Printer, X } from "lucide-react"
import apiClient from "@/lib/api-client"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { ConfirmActionDialog } from "@/components/dashboard/confirm-action-dialog"
import { OrderAllergenBanner } from "@/components/dashboard/kitchen/order-allergen-banner"
import {
  AllergyNoteBlock,
  type AllergyNoteAcknowledgement,
} from "@/components/dashboard/kitchen/allergy-note-block"
import {
  KdsItemList,
  elapsedText,
  orderLabel,
} from "@/components/dashboard/kitchen/kds-card-parts"
import { KdsFulfilmentBadge } from "@/components/dashboard/kitchen/kds-fulfilment-badge"
import { KdsCustomerNote } from "@/components/dashboard/kitchen/kds-customer-note"
import type { OrderDetail, OrderStatus } from "@/types/api"

/**
 * The kitchen board's "New orders" lane (Phase 37-16, D-11; UI-SPEC § K1 "The New lane").
 *
 * WHY IT EXISTS. The board used to read CONFIRMED/PREPARING/READY only, so a storefront order sat in
 * PENDING, invisible to the kitchen, until somebody confirmed it on the Orders page (UXT-020). PENDING is
 * now a kitchen status on both sides (`KitchenStatusesParityTest`), and this lane is where those orders
 * wait for the kitchen to accept them. There is no auto-confirm: the vendor still accepts every order.
 *
 * ALWAYS PRESENT. The section renders from the first paint with a one-line `min-h-14` row, so the board
 * below does not move when the shop list and the first read land (#536). Before the board has been read
 * the row says it is checking rather than "No new orders waiting", which would be a claim nothing had
 * checked yet (the same untruth #556 removed from the header).
 *
 * ACCEPT is the existing `POST /api/v1/orders/{id}/confirm`: STAFF on the order's shop plus
 * `SCOPE_orders:write`, enforced by the server (T-37-43). An `inFlight` ref, not only state, so taps
 * inside one frame send one request (the AllergyNoteBlock pattern). A failure says exactly why, inline
 * in a role=alert on the card, which stays in the lane with both buttons usable again:
 *   - a typed insufficient-stock refusal names the product and the count from the problem's
 *     `productName` / `available` members (GlobalExceptionHandler, 37-16), never parsed from prose;
 *   - any other answer FROM the server re-reads the order, and if it has already left PENDING says it
 *     was accepted or rejected "on another screen" (a second tablet, the Orders page);
 *   - everything else (no answer, or an order still PENDING) is the generic try-again copy.
 *
 * REJECT is the existing `POST /api/v1/orders/{id}/cancel`, for this one order, behind a destructive
 * ConfirmActionDialog. The reason select and bulk reject arrive with the reason column (37-27..37-30);
 * until then the dialog body carries no reason sentence, because there is nowhere to keep one.
 *
 * THE NEW STRIP AND THE RING. The strip is a static label ("NEW · waiting for you to accept"); the only
 * thing that moves is the card's 4px ring, at 1 Hz, via `.kds-new-flash` in globals.css, which is static
 * under prefers-reduced-motion (WCAG 2.3.1).
 */

export const NEW_LANE_HEADING_COPY = "New orders"
export const NEW_LANE_EMPTY_COPY = "No new orders waiting"
export const NEW_LANE_CHECKING_COPY = "Checking for new orders…"
export const ACCEPT_COPY = "Accept order"
export const ACCEPTING_COPY = "Accepting…"
export const REJECT_COPY = "Reject order"
export const ACCEPT_FAILED_COPY = "Couldn't accept this order. Check your connection and try again."
export const REJECT_FAILED_COPY = "Couldn't reject this order. Check your connection and try again."
export const NEW_STRIP_COPY = "NEW · waiting for you to accept"
export const KEEP_ORDER_COPY = "Keep order"
export const REJECT_BODY_COPY =
  "The customer is told the shop couldn't accept the order. Any stock is put back. This can't be undone."

export function acceptRefusedStockCopy(available: number, product: string): string {
  return `Can't accept: only ${available} ${product} left in stock. Update the stock or reject this order.`
}

export function alreadyMovedCopy(outcome: "accepted" | "rejected"): string {
  return `This order was already ${outcome} on another screen.`
}

export function rejectTitleCopy(label: string): string {
  return `Reject order ${label}?`
}

const INSUFFICIENT_STOCK_TYPE = "https://jtoye.uk/errors/insufficient-stock"
/** Statuses that mean someone already accepted the order (it is past PENDING and not cancelled). */
const ACCEPTED_STATUSES: OrderStatus[] = ["CONFIRMED", "PREPARING", "READY", "COMPLETED"]

interface ProblemResponse {
  response?: {
    status?: number
    data?: { type?: string; productName?: unknown; available?: unknown }
  }
}

/**
 * Where did this order go? Asked only after the SERVER refused a transition, so a refusal that is really
 * "someone else got there first" reads as that, not as a connection fault. Returns null when the order is
 * still PENDING or the re-read itself fails: then nothing is known, and the caller says so generically.
 */
async function movedElsewhere(orderId: string): Promise<"accepted" | "rejected" | null> {
  try {
    const res = await apiClient.get(`/api/v1/orders/${orderId}/detail`)
    const status = (res?.data as { status?: OrderStatus } | undefined)?.status
    if (status === "CANCELLED") return "rejected"
    if (status && ACCEPTED_STATUSES.includes(status)) return "accepted"
    return null
  } catch {
    return null
  }
}

/** The copy for a failed Accept, from what the server said (UI-SPEC § Copywriting K1). */
async function describeAcceptFailure(orderId: string, error: unknown): Promise<string> {
  const response = (error as ProblemResponse | null)?.response
  if (!response) return ACCEPT_FAILED_COPY
  const data = response.data
  if (
    data?.type === INSUFFICIENT_STOCK_TYPE &&
    typeof data.available === "number" &&
    typeof data.productName === "string" &&
    data.productName.length > 0
  ) {
    return acceptRefusedStockCopy(data.available, data.productName)
  }
  const moved = await movedElsewhere(orderId)
  return moved ? alreadyMovedCopy(moved) : ACCEPT_FAILED_COPY
}

/** The copy for a failed Reject: already moved when the server says so and the re-read agrees. */
async function describeRejectFailure(orderId: string, error: unknown): Promise<string> {
  if (!(error as ProblemResponse | null)?.response) return REJECT_FAILED_COPY
  const moved = await movedElsewhere(orderId)
  return moved ? alreadyMovedCopy(moved) : REJECT_FAILED_COPY
}

export interface KdsNewLaneProps {
  /** PENDING orders for this board's shop. The lane shows them oldest first. */
  orders: OrderDetail[]
  /** False until the board has been read once; the empty row then says it is still checking. */
  loaded: boolean
  /** The server accepted the order: the caller moves it to the grid as CONFIRMED. */
  onAccepted: (orderId: string) => void
  /** The server cancelled the order: the caller takes it off the board. */
  onRejected: (orderId: string) => void
  onPrint: (order: OrderDetail) => void
  onAllergyNoteAcknowledged: (orderId: string, ack: AllergyNoteAcknowledgement) => void
}

export function KdsNewLane({
  orders,
  loaded,
  onAccepted,
  onRejected,
  onPrint,
  onAllergyNoteAcknowledged,
}: KdsNewLaneProps) {
  const oldestFirst = [...orders].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )
  const count = oldestFirst.length

  return (
    <section aria-labelledby="kds-new-heading" data-testid="kds-new-lane" className="pb-2">
      <div className="mb-3 flex items-center gap-3">
        <h2
          id="kds-new-heading"
          className="text-2xl font-semibold leading-tight text-slate-900"
        >
          {NEW_LANE_HEADING_COPY}
          {count > 0 && (
            <span
              data-testid="kds-new-count"
              className="ml-3 inline-flex min-w-7 items-center justify-center rounded-full bg-orange-700 px-2 py-0.5 align-middle text-sm font-semibold text-white"
            >
              {count}
            </span>
          )}
        </h2>
      </div>

      {count === 0 ? (
        <div
          data-testid="kds-new-lane-row"
          className="flex min-h-14 items-center rounded-lg border border-dashed border-slate-300 bg-white px-4 text-sm text-slate-600"
        >
          {loaded ? NEW_LANE_EMPTY_COPY : NEW_LANE_CHECKING_COPY}
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          <AnimatePresence mode="popLayout">
            {oldestFirst.map((order) => (
              <m.div
                layout
                key={order.id}
                initial={{ opacity: 0, scale: 0.92 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.92 }}
                className="rounded-lg"
              >
                <KdsNewCard
                  order={order}
                  onAccepted={onAccepted}
                  onRejected={onRejected}
                  onPrint={onPrint}
                  onAllergyNoteAcknowledged={onAllergyNoteAcknowledged}
                />
              </m.div>
            ))}
          </AnimatePresence>
        </div>
      )}
    </section>
  )
}

function KdsNewCard({
  order,
  onAccepted,
  onRejected,
  onPrint,
  onAllergyNoteAcknowledged,
}: {
  order: OrderDetail
  onAccepted: (orderId: string) => void
  onRejected: (orderId: string) => void
  onPrint: (order: OrderDetail) => void
  onAllergyNoteAcknowledged: (orderId: string, ack: AllergyNoteAcknowledgement) => void
}) {
  const label = orderLabel(order)
  const [busy, setBusy] = useState<"accept" | "reject" | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmReject, setConfirmReject] = useState(false)
  const inFlight = useRef(false)

  const accept = async () => {
    if (inFlight.current) return
    inFlight.current = true
    setBusy("accept")
    setError(null)
    try {
      await apiClient.post(`/api/v1/orders/${order.id}/confirm`)
      onAccepted(order.id)
    } catch (e: unknown) {
      setError(await describeAcceptFailure(order.id, e))
    } finally {
      inFlight.current = false
      setBusy(null)
    }
  }

  const reject = async () => {
    if (inFlight.current) return
    inFlight.current = true
    setBusy("reject")
    setError(null)
    try {
      await apiClient.post(`/api/v1/orders/${order.id}/cancel`)
      setConfirmReject(false)
      onRejected(order.id)
    } catch (e: unknown) {
      setConfirmReject(false)
      setError(await describeRejectFailure(order.id, e))
    } finally {
      inFlight.current = false
      setBusy(null)
    }
  }

  return (
    <Card data-testid="kds-new-card" className="kds-new-flash overflow-hidden border-2 border-slate-200">
      <div
        data-testid="kds-new-strip"
        className="flex items-center gap-2 bg-orange-700 px-4 py-2 text-sm font-semibold text-white"
      >
        <BellRing aria-hidden="true" className="h-4 w-4 flex-shrink-0" />
        {NEW_STRIP_COPY}
      </div>
      <CardHeader className="pb-3" data-testid="kds-card-header">
        <div className="flex items-start gap-2">
          <CardTitle className="min-w-0 break-words text-lg font-semibold">{label}</CardTitle>
          <KdsFulfilmentBadge fulfilmentType={order.fulfilmentType} />
        </div>
        <OrderAllergenBanner
          allergenNames={order.allergenNames}
          allergenFlags={order.allergenFlags}
        />
        <AllergyNoteBlock
          orderId={order.id}
          orderLabel={label}
          note={order.allergyNote}
          acknowledgedAt={order.allergyNoteAcknowledgedAt}
          acknowledgedBy={order.allergyNoteAcknowledgedBy}
          onAcknowledged={(ack) => onAllergyNoteAcknowledged(order.id, ack)}
        />
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="text-sm font-medium text-slate-700">
          {order.customerName || "Walk-in"}
        </div>
        <KdsCustomerNote note={order.notes} />
        <KdsItemList items={order.items} />
        <div className="flex items-center gap-1 text-xs text-slate-500">
          <Clock className="h-3 w-3" />
          {elapsedText(order.createdAt)}
        </div>

        {error ? (
          <p
            role="alert"
            className="rounded-md border-2 border-red-700 bg-white px-3 py-2 text-sm font-semibold text-red-800"
          >
            {error}
          </p>
        ) : null}

        <div className="flex items-stretch gap-2">
          <Button
            type="button"
            className="h-11 w-full kds-press"
            onClick={accept}
            disabled={busy !== null}
          >
            <Check aria-hidden="true" className="mr-2 h-4 w-4" />
            {busy === "accept" ? ACCEPTING_COPY : ACCEPT_COPY}
            <span className="sr-only"> for order {label}</span>
          </Button>
          <Button
            type="button"
            variant="outline"
            className="h-11 flex-shrink-0 kds-press"
            onClick={() => setConfirmReject(true)}
            disabled={busy !== null}
          >
            <X aria-hidden="true" className="mr-2 h-4 w-4" />
            {REJECT_COPY}
            <span className="sr-only"> for order {label}</span>
          </Button>
          <Button
            type="button"
            variant="outline"
            size="icon"
            onClick={() => onPrint(order)}
            aria-label={`Print ticket ${order.orderNumber || order.id.substring(0, 6)}`}
            title="Print ticket"
            className="h-11 w-11 flex-shrink-0 kds-press"
          >
            <Printer className="h-4 w-4" />
          </Button>
        </div>
      </CardContent>

      <ConfirmActionDialog
        open={confirmReject}
        onOpenChange={setConfirmReject}
        title={rejectTitleCopy(label)}
        description={REJECT_BODY_COPY}
        confirmLabel={REJECT_COPY}
        cancelLabel={KEEP_ORDER_COPY}
        destructive
        onConfirm={reject}
      />
    </Card>
  )
}
