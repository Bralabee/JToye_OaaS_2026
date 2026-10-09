"use client"

import { useRef, useState } from "react"
import { m, AnimatePresence } from "framer-motion"
import { Check, Clock, Printer, X } from "lucide-react"
import apiClient from "@/lib/api-client"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
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
import type { OrderDetail } from "@/types/api"

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
 * inside one frame send one request (the AllergyNoteBlock pattern).
 */

export const NEW_LANE_HEADING_COPY = "New orders"
export const NEW_LANE_EMPTY_COPY = "No new orders waiting"
export const NEW_LANE_CHECKING_COPY = "Checking for new orders…"
export const ACCEPT_COPY = "Accept order"
export const ACCEPTING_COPY = "Accepting…"
export const REJECT_COPY = "Reject order"
export const ACCEPT_FAILED_COPY = "Couldn't accept this order. Check your connection and try again."

export interface KdsNewLaneProps {
  /** PENDING orders for this board's shop. The lane shows them oldest first. */
  orders: OrderDetail[]
  /** False until the board has been read once; the empty row then says it is still checking. */
  loaded: boolean
  /** The server accepted the order: the caller moves it to the grid as CONFIRMED. */
  onAccepted: (orderId: string) => void
  onPrint: (order: OrderDetail) => void
  onAllergyNoteAcknowledged: (orderId: string, ack: AllergyNoteAcknowledgement) => void
}

export function KdsNewLane({
  orders,
  loaded,
  onAccepted,
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
  onPrint,
  onAllergyNoteAcknowledged,
}: {
  order: OrderDetail
  onAccepted: (orderId: string) => void
  onPrint: (order: OrderDetail) => void
  onAllergyNoteAcknowledged: (orderId: string, ack: AllergyNoteAcknowledgement) => void
}) {
  const label = orderLabel(order)
  const [busy, setBusy] = useState<"accept" | null>(null)
  const [error, setError] = useState<string | null>(null)
  const inFlight = useRef(false)

  const accept = async () => {
    if (inFlight.current) return
    inFlight.current = true
    setBusy("accept")
    setError(null)
    try {
      await apiClient.post(`/api/v1/orders/${order.id}/confirm`)
      onAccepted(order.id)
    } catch {
      setError(ACCEPT_FAILED_COPY)
    } finally {
      inFlight.current = false
      setBusy(null)
    }
  }

  return (
    <Card data-testid="kds-new-card" className="border-2 border-slate-200">
      <CardHeader className="pb-3" data-testid="kds-card-header">
        <div className="flex items-start gap-2">
          <CardTitle className="min-w-0 break-words text-lg font-semibold">{label}</CardTitle>
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
    </Card>
  )
}
