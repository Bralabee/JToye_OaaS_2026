"use client"

import { useCallback, useEffect, useState } from "react"
import {
  AlertTriangle,
  Calendar,
  CheckCircle2,
  ClipboardList,
  Loader2,
  Printer,
  RefreshCw,
  UtensilsCrossed,
} from "lucide-react"
import {
  fetchKitchenCookSheet,
  fetchKitchenFulfilmentSlots,
} from "@/lib/kitchen-orders-api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import type { CookSheetDto, FulfilmentSlotDto } from "@/types/api"

export interface KdsCookSheetPanelProps {
  shopId: string | null
  shopName?: string | null
  stopped?: boolean
}

/**
 * Badge shown on individual KDS tickets when the order was placed for a
 * scheduled fulfilment slot window (V87, ADR-0007 / GAP-GTM-02).
 */
export function KdsScheduledWindowBadge({
  scheduledWindowLabel,
  scheduledForDate,
}: {
  scheduledWindowLabel?: string | null
  scheduledForDate?: string | null
}) {
  if (!scheduledWindowLabel || !scheduledWindowLabel.trim()) {
    return null
  }
  return (
    <Badge
      variant="outline"
      data-testid="kds-scheduled-window-badge"
      className="flex flex-shrink-0 items-center gap-1 border-indigo-300 bg-indigo-50 text-xs font-medium text-indigo-900"
    >
      <Calendar className="h-3 w-3" aria-hidden="true" />
      <span>
        Slot: {scheduledWindowLabel}
        {scheduledForDate ? ` (${scheduledForDate})` : ""}
      </span>
    </Badge>
  )
}

/**
 * Consolidated Kitchen "Cook Sheet" Prep View (V87, ADR-0007 / GAP-GTM-03).
 *
 * Aggregates accepted kitchen orders (CONFIRMED, PREPARING, READY) by dish so
 * batch-prep, catering, and weekend pre-order kitchens can see total portions to
 * cook alongside every V73 customer allergy note and V63 recorded allergen set.
 */
export function KdsCookSheetPanel({ shopId, shopName, stopped = false }: KdsCookSheetPanelProps) {
  const [scheduledForDate, setScheduledForDate] = useState<string>("")
  const [fulfilmentSlotId, setFulfilmentSlotId] = useState<string>("")
  const [slots, setSlots] = useState<FulfilmentSlotDto[]>([])
  const [cookSheet, setCookSheet] = useState<CookSheetDto | null>(null)
  const [loading, setLoading] = useState<boolean>(false)
  const [error, setError] = useState<string | null>(null)

  const loadSlots = useCallback(async () => {
    if (!shopId || stopped) {
      setSlots([])
      return
    }
    try {
      const list = await fetchKitchenFulfilmentSlots(shopId)
      setSlots(list)
    } catch {
      setSlots([])
    }
  }, [shopId, stopped])

  const loadCookSheet = useCallback(async () => {
    if (!shopId || stopped) {
      setCookSheet(null)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const data = await fetchKitchenCookSheet({
        shopId,
        scheduledForDate: scheduledForDate.trim() || null,
        fulfilmentSlotId: fulfilmentSlotId.trim() || null,
      })
      setCookSheet(data)
    } catch {
      setError("Could not load the consolidated cook sheet. Try refreshing.")
    } finally {
      setLoading(false)
    }
  }, [shopId, stopped, scheduledForDate, fulfilmentSlotId])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch-on-change effect; async slot list hydration on shop switch.
    void loadSlots()
  }, [loadSlots])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- #709: fetch-on-change effect; the traced sync setLoading prefix IS the loading-UI contract.
    void loadCookSheet()
  }, [loadCookSheet])

  if (!shopId) {
    return null
  }

  const hasFilters = Boolean(scheduledForDate.trim() || fulfilmentSlotId.trim())

  return (
    <Card
      data-testid="kds-cook-sheet-panel"
      className="border-2 border-slate-200 bg-white shadow-sm"
    >
      <CardHeader className="pb-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <div className="flex items-center gap-2">
              <ClipboardList className="h-5 w-5 text-slate-700" aria-hidden="true" />
              <CardTitle className="text-xl font-bold text-slate-900">
                Consolidated Kitchen Cook Sheet
              </CardTitle>
            </div>
            <CardDescription className="mt-1 text-sm text-slate-600">
              Batch prep totals across accepted orders (Confirmed, Preparing, Ready)
              {shopName ? ` for ${shopName}` : ""} &mdash; with recorded allergen sets and
              customer allergy notes.
            </CardDescription>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => void loadCookSheet()}
              disabled={loading}
              data-testid="cook-sheet-refresh-btn"
            >
              {loading ? (
                <Loader2 className="mr-1.5 h-4 w-4 animate-spin" aria-hidden="true" />
              ) : (
                <RefreshCw className="mr-1.5 h-4 w-4" aria-hidden="true" />
              )}
              Refresh
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => window.print()}
              disabled={!cookSheet || cookSheet.items.length === 0}
              data-testid="cook-sheet-print-btn"
            >
              <Printer className="mr-1.5 h-4 w-4" aria-hidden="true" />
              Print cook sheet
            </Button>
          </div>
        </div>

        {/* Filter bar: scheduled date and fulfilment slot window */}
        <div className="mt-3 flex flex-wrap items-end gap-3 border-t border-slate-100 pt-3">
          <div className="flex flex-col gap-1">
            <label
              htmlFor="cook-sheet-date-input"
              className="text-xs font-medium text-slate-600"
            >
              Scheduled service date
            </label>
            <input
              id="cook-sheet-date-input"
              data-testid="cook-sheet-date-input"
              aria-label="Cook sheet date"
              type="date"
              value={scheduledForDate}
              onChange={(e) => setScheduledForDate(e.target.value)}
              className="h-9 rounded-md border border-slate-300 bg-white px-2.5 text-sm text-slate-900 focus:border-slate-500 focus:outline-none"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label
              htmlFor="cook-sheet-slot-select"
              className="text-xs font-medium text-slate-600"
            >
              Fulfilment window
            </label>
            <select
              id="cook-sheet-slot-select"
              data-testid="cook-sheet-slot-select"
              aria-label="Cook sheet slot"
              value={fulfilmentSlotId}
              onChange={(e) => setFulfilmentSlotId(e.target.value)}
              className="h-9 rounded-md border border-slate-300 bg-white px-2.5 text-sm text-slate-900 focus:border-slate-500 focus:outline-none"
            >
              <option value="">All active orders &amp; slots</option>
              {slots.map((slot) => (
                <option key={slot.id} value={slot.id}>
                  {slot.windowLabel} ({slot.reservedOrders}
                  {slot.maxOrders !== null ? `/${slot.maxOrders}` : ""} orders)
                </option>
              ))}
            </select>
          </div>

          {hasFilters && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              data-testid="cook-sheet-clear-filters"
              onClick={() => {
                setScheduledForDate("")
                setFulfilmentSlotId("")
              }}
              className="h-9 text-xs text-slate-600 hover:text-slate-900"
            >
              Clear filters
            </Button>
          )}
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {error && (
          <div
            role="alert"
            data-testid="cook-sheet-error"
            className="rounded-lg border border-red-200 bg-red-50 px-3 py-2.5 text-sm text-red-800"
          >
            {error}
          </div>
        )}

        {cookSheet && (
          <>
            {/* Summary KPIs */}
            <div
              data-testid="cook-sheet-summary"
              className="grid grid-cols-1 gap-3 sm:grid-cols-3"
            >
              <div className="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5">
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Accepted orders in batch
                </p>
                <p
                  data-testid="cook-sheet-total-orders"
                  className="mt-0.5 text-2xl font-bold tabular-nums text-slate-900"
                >
                  {cookSheet.totalOrders}
                </p>
              </div>
              <div className="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5">
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Total portions / units to prep
                </p>
                <p
                  data-testid="cook-sheet-total-units"
                  className="mt-0.5 text-2xl font-bold tabular-nums text-slate-900"
                >
                  {cookSheet.totalUnits}
                </p>
              </div>
              <div className="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5">
                <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  Customer allergy notes
                </p>
                <p
                  data-testid="cook-sheet-alert-count"
                  className="mt-0.5 text-2xl font-bold tabular-nums text-slate-900"
                >
                  {cookSheet.allergyAlerts.length}
                </p>
              </div>
            </div>

            {/* V73 Customer Allergy Alerts — highlighted before batch items so no note is missed */}
            {cookSheet.allergyAlerts.length > 0 && (
              <div
                data-testid="cook-sheet-allergy-alerts"
                role="region"
                aria-label="Customer allergy notes in this batch"
                className="rounded-lg border-2 border-amber-500 bg-amber-50 p-3.5"
              >
                <div className="flex items-center gap-2 text-sm font-bold text-amber-950">
                  <AlertTriangle className="h-4 w-4 flex-shrink-0 text-amber-700" aria-hidden="true" />
                  <span>
                    Allergy &amp; dietary instructions in this batch ({cookSheet.allergyAlerts.length})
                  </span>
                </div>
                <p className="mt-0.5 text-xs text-amber-900">
                  Prepare and label these orders separately from standard bulk trays.
                </p>
                <ul className="mt-2.5 space-y-2">
                  {cookSheet.allergyAlerts.map((alert) => (
                    <li
                      key={alert.orderId}
                      data-testid="cook-sheet-allergy-alert-row"
                      className="flex flex-col justify-between gap-2 rounded-md border border-amber-200 bg-white px-3 py-2 text-sm sm:flex-row sm:items-center"
                    >
                      <div>
                        <span className="font-semibold text-slate-900">
                          {alert.orderNumber}
                        </span>
                        <span className="mx-1.5 text-slate-400">&middot;</span>
                        <span className="font-medium text-slate-700">
                          {alert.customerName || "Walk-in"}
                        </span>
                        <p className="mt-0.5 text-sm font-medium text-amber-950">
                          &ldquo;{alert.allergyNote}&rdquo;
                        </p>
                      </div>
                      <Badge
                        data-testid="cook-sheet-allergy-ack-badge"
                        className={
                          alert.acknowledged
                            ? "border-emerald-200 bg-emerald-50 text-emerald-800"
                            : "border-amber-300 bg-amber-100 text-amber-950"
                        }
                        variant="outline"
                      >
                        {alert.acknowledged ? (
                          <span className="flex items-center gap-1">
                            <CheckCircle2 className="h-3 w-3" aria-hidden="true" />
                            Note read
                          </span>
                        ) : (
                          "Unread on ticket"
                        )}
                      </Badge>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {/* Consolidated Dish Tally */}
            {cookSheet.items.length === 0 ? (
              <div
                data-testid="cook-sheet-empty"
                className="flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-200 py-8 text-center"
              >
                <UtensilsCrossed className="mb-2 h-8 w-8 text-slate-300" aria-hidden="true" />
                <p className="text-sm font-medium text-slate-700">
                  No accepted dishes in this batch
                </p>
                <p className="mt-0.5 text-xs text-slate-500">
                  Once orders are confirmed, their combined dish counts appear here.
                </p>
              </div>
            ) : (
              <div
                data-testid="cook-sheet-items"
                className="overflow-hidden rounded-lg border border-slate-200"
              >
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-slate-200 bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-600">
                    <tr>
                      <th scope="col" className="px-3.5 py-2.5">
                        Qty to prep
                      </th>
                      <th scope="col" className="px-3.5 py-2.5">
                        Dish
                      </th>
                      <th scope="col" className="px-3.5 py-2.5">
                        Orders
                      </th>
                      <th scope="col" className="px-3.5 py-2.5">
                        Recorded allergens (PPDS)
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-200 bg-white">
                    {cookSheet.items.map((item) => (
                      <tr
                        key={`${item.productId}-${item.productName}`}
                        data-testid="cook-sheet-item-row"
                      >
                        <td className="whitespace-nowrap px-3.5 py-2.5">
                          <span
                            data-testid="cook-sheet-item-qty"
                            className="inline-flex items-center rounded-md bg-slate-900 px-2.5 py-1 text-base font-bold tabular-nums text-white"
                          >
                            {item.totalQuantity} &times;
                          </span>
                        </td>
                        <td className="px-3.5 py-2.5 font-semibold text-slate-900">
                          {item.productName}
                        </td>
                        <td className="whitespace-nowrap px-3.5 py-2.5 text-slate-600 tabular-nums">
                          {item.orderCount} {item.orderCount === 1 ? "order" : "orders"}
                        </td>
                        <td className="px-3.5 py-2.5">
                          {item.allergenMask === null || item.allergenNames === null ? (
                            <Badge
                              variant="outline"
                              data-testid="cook-sheet-allergen-unrecorded"
                              className="border-amber-300 bg-amber-50 text-xs font-medium text-amber-900"
                            >
                              Allergens not recorded
                            </Badge>
                          ) : item.allergenNames.length === 0 ? (
                            <span
                              data-testid="cook-sheet-allergen-none"
                              className="text-xs text-slate-500"
                            >
                              None of the 14 declared
                            </span>
                          ) : (
                            <div
                              data-testid="cook-sheet-allergen-list"
                              className="flex flex-wrap gap-1"
                            >
                              {item.allergenNames.map((name) => (
                                <Badge
                                  key={name}
                                  variant="outline"
                                  className="border-red-200 bg-red-50 text-xs font-semibold text-red-900"
                                >
                                  {name}
                                </Badge>
                              ))}
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
