"use client"

import { useRef, useState } from "react"
import { m, AnimatePresence } from "framer-motion"
import { Ban, BellRing, Check, Clock, Printer, X } from "lucide-react"
import apiClient from "@/lib/api-client"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  RejectOrdersDialog,
  type RejectableOrder,
} from "@/components/dashboard/orders/reject-orders-dialog"
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
import { UnverifiedContactBadge } from "@/components/dashboard/kitchen/unverified-contact-badge"
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
 * REJECT (37-30, UI-SPEC § D1; D-18, SC-4). Both the per-card "Reject order" and the lane's selection
 * mode open the shared `RejectOrdersDialog`: a required reason kept on the order and never sent to
 * the customer, then a per-order result summary. Every submit is 37-29's `POST /orders/bulk-cancel`,
 * a batch of one included, so an order a colleague accepted a second earlier is reported
 * ALREADY_ACCEPTED rather than cancelled (the single cancel would cancel a CONFIRMED order too). The
 * caller removes the ids the server rejected; when any id was refused the board is re-read on close,
 * so the lane stops showing an order that has already moved.
 *
 * SELECTION MODE. "Reject several orders" in the lane header (shown at two or more cards) puts a 24px
 * checkbox in a 44px row on every New card and a sticky, safe-area-padded bar at the bottom: "{n}
 * selected" · "Reject {n} orders" · "Stop selecting". Selection never covers Accept — the card's
 * actions stay exactly where they are and stay usable.
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
export const NEW_STRIP_COPY = "NEW · waiting for you to accept"
export const BULK_REJECT_COPY = "Reject several orders"
export const STOP_SELECTING_COPY = "Stop selecting"
export const SELECT_CARD_COPY = "Select this order"

export function acceptRefusedStockCopy(available: number, product: string): string {
  return `Can't accept: only ${available} ${product} left in stock. Update the stock or reject this order.`
}

export function alreadyMovedCopy(outcome: "accepted" | "rejected"): string {
  return `This order was already ${outcome} on another screen.`
}

export function selectedCountCopy(count: number): string {
  return `${count} selected`
}

/** The bar's confirm (UI-SPEC § K1 "Bulk reject"), singular at one. */
export function rejectSelectedCopy(count: number): string {
  return count === 1 ? "Reject 1 order" : `Reject ${count} orders`
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

export interface KdsNewLaneProps {
  /** PENDING orders for this board's shop. The lane shows them oldest first. */
  orders: OrderDetail[]
  /** False until the board has been read once; the empty row then says it is still checking. */
  loaded: boolean
  /** The server accepted the order: the caller moves it to the grid as CONFIRMED. */
  onAccepted: (orderId: string) => void
  /** The server cancelled the order: the caller takes it off the board. */
  onRejected: (orderId: string) => void
  /**
   * 37-30: a reject summary closed with at least one order the server would NOT reject (accepted
   * on another screen, already cancelled, not found) — the caller re-reads the board so the lane
   * stops showing an order that has already moved.
   */
  onRefresh?: () => void
  onPrint: (order: OrderDetail) => void
  onAllergyNoteAcknowledged: (orderId: string, ack: AllergyNoteAcknowledgement) => void
}

export function KdsNewLane({
  orders,
  loaded,
  onAccepted,
  onRejected,
  onRefresh,
  onPrint,
  onAllergyNoteAcknowledged,
}: KdsNewLaneProps) {
  const oldestFirst = [...orders].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  )
  const count = oldestFirst.length

  // 37-30 selection mode. Ids only; what is selected is re-derived against the cards on screen,
  // so a card that left the lane under the selection drops out without an effect.
  const [selecting, setSelecting] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [bulkDialogOpen, setBulkDialogOpen] = useState(false)
  const bulkRefusedRef = useRef(false)

  const selectedOrders: RejectableOrder[] = oldestFirst
    .filter((o) => selectedIds.has(o.id))
    .map((o) => ({ id: o.id, label: orderLabel(o) }))

  const stopSelecting = () => {
    setSelecting(false)
    setSelectedIds(new Set())
  }

  const toggleSelected = (orderId: string, checked: boolean) => {
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (checked) next.add(orderId)
      else next.delete(orderId)
      return next
    })
  }

  return (
    <section aria-labelledby="kds-new-heading" data-testid="kds-new-lane" className="pb-2">
      <div className="mb-3 flex flex-wrap items-center gap-3">
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
        {/* UI-SPEC § K1 "Bulk reject": shown when the lane has two or more cards; while selecting,
            the bar's "Stop selecting" is the way out, so the button steps aside. */}
        {count >= 2 && !selecting && (
          <Button
            type="button"
            variant="outline"
            className="h-11 kds-press sm:ml-auto"
            onClick={() => setSelecting(true)}
          >
            <Ban aria-hidden="true" className="mr-2 h-4 w-4" />
            {BULK_REJECT_COPY}
          </Button>
        )}
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
                  selecting={selecting}
                  selected={selectedIds.has(order.id)}
                  onSelectedChange={(checked) => toggleSelected(order.id, checked)}
                  onAccepted={onAccepted}
                  onRejected={onRejected}
                  onRefresh={onRefresh}
                  onPrint={onPrint}
                  onAllergyNoteAcknowledged={onAllergyNoteAcknowledged}
                />
              </m.div>
            ))}
          </AnimatePresence>
        </div>
      )}

      {/* The sticky action bar (UI-SPEC § K1 "Bulk reject"): bottom-fixed, safe-area padded, present
          while selecting. The same bar as the Orders page's (§ D1). */}
      {selecting && (
        <div
          role="region"
          aria-label="Selected orders"
          data-testid="kds-selection-bar"
          className="fixed inset-x-0 bottom-0 z-40 border-t border-slate-200 bg-white px-4 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] shadow-[0_-4px_12px_rgba(15,23,42,0.08)]"
        >
          <div className="mx-auto flex max-w-screen-2xl flex-wrap items-center gap-3">
            <span className="text-sm font-semibold text-slate-900" aria-live="polite">
              {selectedCountCopy(selectedOrders.length)}
            </span>
            <Button
              type="button"
              variant="outline"
              className="h-11 border-red-700 text-red-800 hover:bg-red-50 hover:text-red-900 kds-press"
              disabled={selectedOrders.length === 0}
              onClick={() => setBulkDialogOpen(true)}
            >
              <Ban aria-hidden="true" className="mr-2 h-4 w-4" />
              {rejectSelectedCopy(selectedOrders.length)}
            </Button>
            <Button type="button" variant="ghost" className="h-11 kds-press" onClick={stopSelecting}>
              {STOP_SELECTING_COPY}
            </Button>
          </div>
        </div>
      )}

      <RejectOrdersDialog
        open={bulkDialogOpen}
        onOpenChange={setBulkDialogOpen}
        orders={selectedOrders}
        onRejected={(ids) => {
          bulkRefusedRef.current = ids.length < selectedOrders.length
          ids.forEach(onRejected)
        }}
        onClosedAfterSubmit={() => {
          stopSelecting()
          if (bulkRefusedRef.current) onRefresh?.()
          bulkRefusedRef.current = false
        }}
      />
    </section>
  )
}

function KdsNewCard({
  order,
  selecting,
  selected,
  onSelectedChange,
  onAccepted,
  onRejected,
  onRefresh,
  onPrint,
  onAllergyNoteAcknowledged,
}: {
  order: OrderDetail
  selecting: boolean
  selected: boolean
  onSelectedChange: (checked: boolean) => void
  onAccepted: (orderId: string) => void
  onRejected: (orderId: string) => void
  onRefresh?: () => void
  onPrint: (order: OrderDetail) => void
  onAllergyNoteAcknowledged: (orderId: string, ack: AllergyNoteAcknowledgement) => void
}) {
  const label = orderLabel(order)
  const [busy, setBusy] = useState<"accept" | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirmReject, setConfirmReject] = useState(false)
  // The ids the server rejected, held until the summary closes: this dialog lives INSIDE the
  // card, so taking the order off the board at once would unmount the card and the summary
  // with it, before the kitchen could read it (UI-SPEC § D1: it does not auto-close).
  const rejectedIdsRef = useRef<string[]>([])
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
        {/* Wraps, so the fulfilment badge drops below a long number instead of squeezing it
            (the desktop finding recorded on the grid card's row in page.tsx). */}
        <div className="flex flex-wrap items-start gap-2">
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
        {/* 37-30 selection mode: a 24px box in a 44px row, labelled by the order number. It sits
            above the customer block, never over the actions, so Accept stays where it is. */}
        {selecting && (
          <div
            data-testid="kds-select-row"
            className="flex min-h-11 items-center gap-3 rounded-md border border-slate-200 bg-slate-50 px-3"
          >
            <Checkbox
              aria-label={`Select order ${label}`}
              checked={selected}
              onCheckedChange={(checked) => onSelectedChange(checked === true)}
            />
            <span aria-hidden="true" className="text-sm font-medium text-slate-700">
              {SELECT_CARD_COPY}
            </span>
          </div>
        )}
        {/* Customer name + the unverified-contact badge (37-30, F20): read from the stored fact,
            rendered only for `false`, never for null/absent (not recorded). */}
        <div className="flex flex-wrap items-center gap-2 text-sm font-medium text-slate-700">
          <span>{order.customerName || "Walk-in"}</span>
          <UnverifiedContactBadge contactVerified={order.contactVerified} />
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

      {/* One order is a batch of one: the same dialog, the same reason, the same outcomes. The
          card leaves the lane when the summary is closed, not before (see rejectedIdsRef). */}
      <RejectOrdersDialog
        open={confirmReject}
        onOpenChange={setConfirmReject}
        orders={[{ id: order.id, label }]}
        onRejected={(ids) => {
          rejectedIdsRef.current = ids
        }}
        onClosedAfterSubmit={() => {
          const ids = rejectedIdsRef.current
          rejectedIdsRef.current = []
          if (ids.length === 0) onRefresh?.()
          ids.forEach(onRejected)
        }}
      />
    </Card>
  )
}
