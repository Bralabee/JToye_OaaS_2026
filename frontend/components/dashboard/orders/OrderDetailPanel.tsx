"use client"

import { useMemo, useState } from "react"
import {
  ShoppingCart,
  Clock,
  CheckCircle2,
  ChefHat,
  Package as PackageIcon,
  FileCheck,
  XCircle,
  RotateCcw,
} from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { RefundDialog } from "./RefundDialog"
import { OrderAllergenBanner } from "@/components/dashboard/kitchen/order-allergen-banner"
import { ItemAllergenBadge } from "@/components/dashboard/kitchen/item-allergen-badge"
import type { OrderDetail, OrderStatus, Refund } from "@/types/api"
import { formatUkDateTime } from "@/lib/uk-datetime"
import { AllergyNoteBlock } from "@/components/dashboard/kitchen/allergy-note-block"
import { acknowledgementStatement } from "@/components/storefront/recorded-allergen-set"
import {
  CUSTOMER_CONFIRMATION_NOT_RECORDED_COPY,
  VENDOR_PLACED_ORDER_COPY,
  customerConfirmedAllergensCopy,
  customerConfirmedNoneCopy,
} from "@/lib/allergen-copy"

/**
 * OrderDetailPanel
 * ----------------
 * The full vendor-facing order detail view, extracted from
 * `frontend/app/dashboard/orders/page.tsx` lines ~813-940 and extended with:
 *   - Payment block (paymentStatus / paymentMethod / paymentReference)
 *   - Refund history block (one row per Refund)
 *   - Action panel with an "Issue refund" button gated by visibility predicate
 *   - QA-council FE-7: the V63/Phase-31-10 write-time allergen snapshot,
 *     reusing the SAME `OrderAllergenBanner` / `ItemAllergenBadge` the kitchen
 *     display renders — not a re-derived copy, so the vendor's own order view
 *     and the kitchen ticket can never disagree about what was declared. See
 *     that component's own docstring for the null/[]/non-empty distinction
 *     this must never collapse (a historic order must never read as
 *     allergen-free, and the advisory `allergenFlags` reconciliation must
 *     never be merged into the vendor's declared set).
 *

 * Visibility predicate for "Issue refund":
 *   - order.status     ∈ {CONFIRMED, PREPARING, READY, COMPLETED}
 *   - order.paymentStatus === "CAPTURED"
 *   - order.paymentReference is non-empty
 *   - remaining refundable amount > 0
 *
 * Matches the food-delivery palette (orange/emerald/slate) per
 * `feedback_design_direction.md` memory — no new design system primitives.
 */

type StatusUiConfig = {
  label: string
  bgColor: string
  icon: React.ComponentType<{ className?: string }>
}

const STATUS_CONFIG: Record<OrderStatus, StatusUiConfig> = {
  DRAFT:     { label: "Draft",     bgColor: "bg-slate-500",   icon: Clock },
  // bg-yellow-700, not -500: white text on -500 is 1.92:1 on white — fails AA
  // (F3 / A11Y-1). -700 is 4.92:1.
  PENDING:   { label: "Pending",   bgColor: "bg-yellow-700",  icon: Clock },
  CONFIRMED: { label: "Confirmed", bgColor: "bg-blue-500",    icon: CheckCircle2 },
  PREPARING: { label: "Preparing", bgColor: "bg-amber-500",  icon: ChefHat },
  READY:     { label: "Ready",     bgColor: "bg-green-500",   icon: PackageIcon },
  COMPLETED: { label: "Completed", bgColor: "bg-emerald-600", icon: FileCheck },
  CANCELLED: { label: "Cancelled", bgColor: "bg-red-500",     icon: XCircle },
  REFUNDED:  { label: "Refunded",  bgColor: "bg-orange-500",  icon: RotateCcw },
}

const REFUNDABLE_STATUSES = new Set<OrderStatus>([
  "CONFIRMED",
  "PREPARING",
  "READY",
  "COMPLETED",
])

// Refund statuses that count toward the "already refunded" bucket — anything
// not in a terminal failed/canceled state. Matches the backend's refundable
// arithmetic in RefundService (see Phase 17-01).
const COUNTS_TOWARD_REFUNDED = new Set<Refund["status"]>([
  "CREATING",
  "pending",
  "requires_action",
  "succeeded",
])

function formatPounds(pennies: number | undefined | null): string {
  return ((pennies ?? 0) / 100).toFixed(2)
}

function refundStatusClass(status: Refund["status"]): string {
  if (status === "succeeded") return "text-emerald-700 font-medium"
  if (status === "failed") return "text-red-600 font-medium"
  if (status === "canceled") return "text-slate-500"
  return "text-orange-700 font-medium"
}

/**
 * 31.1-22 (D-07, #784): how the order was placed and what the customer confirmed, in one sentence
 * for the vendor. The four states come from acknowledgementStatement — the SAME mapping the
 * customer's tracking page and My Orders use (31.1-18) — so the vendor and the customer can never
 * be told different things about which state an order is in; only the voice differs.
 *
 *   list         — "Customer confirmed allergens: Gluten, Milk — 3 October 2026, 18:02"
 *   none         — the customer confirmed a basket that declared none of the 14 (a statement)
 *   vendor       — "Placed by the shop — no customer allergen confirmation recorded" (D-07)
 *   not-recorded — an order from before confirmations were recorded; no claim either way
 */
function allergenConfirmationLine(order: OrderDetail): string {
  const when = order.allergenAckAt ? formatUkDateTime(order.allergenAckAt) || null : null
  const statement = acknowledgementStatement(order.acknowledgedAllergenNames, order.placedVia)
  switch (statement.kind) {
    case "list":
      return customerConfirmedAllergensCopy(statement.names, when)
    case "none":
      return customerConfirmedNoneCopy(when)
    case "vendor":
      return VENDOR_PLACED_ORDER_COPY
    default:
      return CUSTOMER_CONFIRMATION_NOT_RECORDED_COPY
  }
}

interface OrderDetailPanelProps {
  order: OrderDetail
  onRefundIssued?: () => void
}

export function OrderDetailPanel({ order, onRefundIssued }: OrderDetailPanelProps) {
  const [refundDialogOpen, setRefundDialogOpen] = useState(false)

  const statusUi = STATUS_CONFIG[order.status] ?? STATUS_CONFIG.DRAFT
  const StatusIcon = statusUi.icon

  const totalAlreadyRefunded = useMemo(() => {
    return (order.refunds ?? [])
      .filter((r) => COUNTS_TOWARD_REFUNDED.has(r.status))
      .reduce((sum, r) => sum + (r.amountPennies ?? 0), 0)
  }, [order.refunds])

  const remainingPennies = Math.max(
    0,
    (order.totalAmountPennies ?? 0) - totalAlreadyRefunded
  )

  const canRefund =
    REFUNDABLE_STATUSES.has(order.status) &&
    order.paymentStatus === "CAPTURED" &&
    !!order.paymentReference &&
    remainingPennies > 0

  const refunds = order.refunds ?? []

  return (
    <div className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      {/* Header */}
      <div className="flex flex-col gap-2 border-b border-slate-100 pb-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="flex items-center gap-2 text-xl font-semibold text-slate-900">
            <ShoppingCart className="h-5 w-5 text-slate-600" aria-hidden="true" />
            <span>{order.orderNumber || order.id.substring(0, 8)}</span>
          </h2>
          <p className="mt-1 text-sm text-slate-500">
            Created {formatUkDateTime(order.createdAt)}
          </p>
        </div>
        <div className="flex flex-col items-start gap-2 sm:items-end">
          <Badge
            className={`${statusUi.bgColor} flex items-center gap-1 text-white`}
          >
            <StatusIcon className="h-3 w-3" aria-hidden="true" />
            {statusUi.label}
          </Badge>
          <span className="text-2xl font-bold text-slate-900">
            £{formatPounds(order.totalAmountPennies)}
          </span>
        </div>
      </div>

      {/* QA-council FE-7: the order's allergen picture, above the customer/
          items blocks — this is a safety fact, not incidental metadata, and
          the shared component already renders NOTHING for the common "vendor
          declared none of the 14, nothing flagged" case. */}
      <OrderAllergenBanner
        allergenNames={order.allergenNames}
        allergenFlags={order.allergenFlags}
      />

      {/* 31.1-22 (#812, D-15): the customer's allergy note and the act of marking it read —
          the same block the kitchen board shows, so the two surfaces cannot disagree about
          whether it was read. Above the customer block, and so above the delivery notes it is
          never merged into. Renders nothing for an order without a note. */}
      <AllergyNoteBlock
        orderId={order.id}
        orderLabel={order.orderNumber || order.id.substring(0, 8)}
        note={order.allergyNote}
        acknowledgedAt={order.allergyNoteAcknowledgedAt}
        acknowledgedBy={order.allergyNoteAcknowledgedBy}
      />

      {/* 31.1-22 (D-07): how the order was placed — a customer who confirmed the allergens, the
          shop keying it in, or an order from before confirmations were recorded. */}
      <p
        data-testid="order-allergen-confirmation"
        className="rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 text-sm font-medium text-slate-800"
      >
        {allergenConfirmationLine(order)}
      </p>

      {/* Customer block */}
      <div className="grid grid-cols-1 gap-4 rounded-lg border border-slate-200 p-4 sm:grid-cols-2">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
            Customer
          </p>
          <p className="font-medium text-slate-900">
            {order.customerName || "N/A"}
          </p>
        </div>
        {order.customerEmail && (
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Email
            </p>
            <p className="text-sm text-slate-700">{order.customerEmail}</p>
          </div>
        )}
        {order.customerPhone && (
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Phone
            </p>
            <p className="text-sm text-slate-700">{order.customerPhone}</p>
          </div>
        )}
        {order.notes && (
          <div className="sm:col-span-2">
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Notes
            </p>
            <p className="text-sm text-slate-700">{order.notes}</p>
          </div>
        )}
      </div>

      {/* Payment block (only when backend provides paymentStatus) */}
      {order.paymentStatus && (
        <div className="grid grid-cols-1 gap-4 rounded-lg border border-slate-200 p-4 sm:grid-cols-2">
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Payment status
            </p>
            <p className="font-medium text-slate-900">{order.paymentStatus}</p>
          </div>
          {order.paymentMethod && (
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Method
              </p>
              <p className="text-sm text-slate-700">{order.paymentMethod}</p>
            </div>
          )}
          {order.paymentReference && (
            <div className="sm:col-span-2">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Stripe reference
              </p>
              <p className="break-all font-mono text-xs text-slate-700">
                {order.paymentReference}
              </p>
            </div>
          )}
        </div>
      )}

      {/* Fulfilment block — delivery/collection + (for delivery) the address.
          Read-only render of PII (T-19-07-01): the address is never logged. */}
      {order.fulfilmentType && (
        <div className="grid grid-cols-1 gap-4 rounded-lg border border-slate-200 p-4 sm:grid-cols-2">
          <div>
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
              Fulfilment
            </p>
            <p className="font-medium text-slate-900">
              {order.fulfilmentType === "COLLECTION" ? "Collection" : "Delivery"}
            </p>
          </div>
          {order.fulfilmentType === "DELIVERY" && (
            <div data-testid="delivery-address">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Delivery address
              </p>
              <address className="text-sm not-italic text-slate-700">
                {order.addressLine1 && (
                  <span className="block">{order.addressLine1}</span>
                )}
                {order.addressLine2 && (
                  <span className="block">{order.addressLine2}</span>
                )}
                {order.addressCity && (
                  <span className="block">{order.addressCity}</span>
                )}
                {order.addressPostcode && (
                  <span className="block font-medium">{order.addressPostcode}</span>
                )}
              </address>
            </div>
          )}
        </div>
      )}

      {/* Line items block */}
      <div>
        <h3 className="mb-3 text-sm font-semibold text-slate-700">
          Items ({order.items?.length || 0})
        </h3>
        {order.items && order.items.length > 0 ? (
          <div className="overflow-hidden rounded-lg border border-slate-200">
            <Table containerLabel="Order items table">
              <TableHeader>
                <TableRow>
                  <TableHead>Product</TableHead>
                  <TableHead className="text-center">Qty</TableHead>
                  <TableHead className="text-right">Unit</TableHead>
                  <TableHead className="text-right">Total</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {order.items.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell className="font-medium">
                      <div className="flex flex-wrap items-center gap-2">
                        <span>{item.productName || item.productId.substring(0, 8)}</span>
                        {/* QA-council FE-7: this LINE's own write-time snapshot —
                            renders nothing for a pre-V63 line (absent/null), which
                            is deliberate: the order-level banner above already
                            carries the "not recorded" statement once. */}
                        <ItemAllergenBadge allergenNames={item.allergenNames} />
                      </div>
                    </TableCell>
                    <TableCell className="text-center">{item.quantity}</TableCell>
                    <TableCell className="text-right">
                      £{formatPounds(item.unitPricePennies)}
                    </TableCell>
                    <TableCell className="text-right font-semibold">
                      £{formatPounds(item.totalPricePennies)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ) : (
          <p className="rounded-lg border border-dashed border-slate-200 py-4 text-center text-sm text-slate-500">
            No items in this order.
          </p>
        )}
      </div>

      {/* Refund history block */}
      {refunds.length > 0 && (
        <div>
          <h3 className="mb-3 text-sm font-semibold text-slate-700">
            Refunds ({refunds.length})
          </h3>
          <div className="space-y-2">
            {refunds.map((refund) => {
              const reasonText = refund.reason
                .replaceAll("_", " ")
                .toLowerCase()
              return (
                <div
                  key={refund.id}
                  className="flex flex-col gap-2 rounded-lg border border-slate-200 p-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div>
                    <p className="font-semibold text-slate-900">
                      £{formatPounds(refund.amountPennies)}
                    </p>
                    <p className="text-xs text-slate-500">
                      {reasonText}
                      {refund.reasonNote ? ` — ${refund.reasonNote}` : ""}
                    </p>
                  </div>
                  <div className="text-left sm:text-right">
                    <span className={refundStatusClass(refund.status)}>
                      {refund.status}
                    </span>
                    <p className="text-xs text-slate-500">
                      {formatUkDateTime(refund.requestedAt)}
                    </p>
                    {refund.failureReason && (
                      <p className="text-xs text-red-600">
                        {refund.failureReason}
                      </p>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
          <p className="mt-2 text-xs text-slate-500">
            Already refunded: £{formatPounds(totalAlreadyRefunded)} · Remaining:
            £{formatPounds(remainingPennies)}
          </p>
        </div>
      )}

      {/* Action panel */}
      <div className="flex flex-wrap items-center justify-end gap-2 border-t border-slate-100 pt-4">
        {canRefund && (
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="border-orange-500 text-orange-700 hover:bg-orange-50 hover:text-orange-800"
            onClick={() => setRefundDialogOpen(true)}
          >
            <RotateCcw className="mr-1 h-3 w-3" aria-hidden="true" />
            Issue refund
          </Button>
        )}
      </div>

      <RefundDialog
        open={refundDialogOpen}
        onOpenChange={setRefundDialogOpen}
        orderId={order.id}
        remainingPennies={remainingPennies}
        onSuccess={() => {
          setRefundDialogOpen(false)
          onRefundIssued?.()
        }}
      />
    </div>
  )
}

export default OrderDetailPanel
