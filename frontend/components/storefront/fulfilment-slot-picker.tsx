"use client"

import { CalendarClock, CheckCircle2, Clock, Sparkles, AlertCircle } from "lucide-react"
import type { FulfilmentSlotDto, FulfilmentTimingMode } from "@/types/api"

/**
 * Storefront Scheduled Fulfilment Slot Picker (GAP-GTM-03, V87 / ADR-0007).
 *
 * Renders on `/shop/[slug]/checkout` when the shop's `fulfilmentTimingMode` is
 * `SCHEDULED_SLOTS_ONLY` or `HYBRID_IMMEDIATE_AND_SLOTS`. Returns `null` when
 * `IMMEDIATE_ONLY` or absent so classic immediate-preparation shops keep their
 * exact pre-V87 checkout surface.
 */

export const SLOT_REQUIRED_PROBLEM_TYPE = "https://jtoye.uk/errors/fulfilment-slot-required"
export const SLOT_CLOSED_PROBLEM_TYPE = "https://jtoye.uk/errors/fulfilment-slot-closed"
export const SLOT_CUTOFF_PASSED_PROBLEM_TYPE = "https://jtoye.uk/errors/fulfilment-slot-cutoff-passed"
export const SLOT_FULL_PROBLEM_TYPE = "https://jtoye.uk/errors/fulfilment-slot-full"

/**
 * Maps V87 RFC 9457 slot problem types (`OrderFulfilmentSlotException` / `GlobalExceptionHandler`)
 * to clear customer-facing remediation copy on checkout.
 */
export function describeSlotCheckoutError(
  data: { type?: string; detail?: string } | undefined
): string | null {
  if (!data?.type) return null
  switch (data.type) {
    case SLOT_REQUIRED_PROBLEM_TYPE:
      return (
        data.detail ||
        "This kitchen requires a scheduled fulfilment slot. Please choose an available window above."
      )
    case SLOT_CLOSED_PROBLEM_TYPE:
      return (
        data.detail ||
        "That fulfilment window is now closed. Please pick another available slot."
      )
    case SLOT_CUTOFF_PASSED_PROBLEM_TYPE:
      return (
        data.detail ||
        "The order cutoff for that fulfilment window has passed. Please pick a later slot."
      )
    case SLOT_FULL_PROBLEM_TYPE:
      return (
        data.detail ||
        "That fulfilment window just sold out. Please select another available slot."
      )
    default:
      return null
  }
}

/**
 * Mirrors `PublicStorefrontService.validateStorefrontTimingAndAvailability` (V87 / ADR-0007):
 * - A `paused` shop blocks all storefront checkout orders (both immediate and scheduled pre-orders).
 * - A `closed` shop (`openNow === false`) blocks immediate preparation (`IMMEDIATE_ONLY`, or
 *   `HYBRID_IMMEDIATE_AND_SLOTS` when `selectedSlotId === null`), but PERMITS scheduled slot
 *   pre-orders (`SCHEDULED_SLOTS_ONLY`, or `HYBRID_IMMEDIATE_AND_SLOTS` with `selectedSlotId !== null`).
 */
export function isCheckoutBlockedByAvailability(params: {
  availabilityKind: string | null | undefined
  timingMode: FulfilmentTimingMode | null | undefined
  selectedSlotId: string | null
}): boolean {
  const { availabilityKind, timingMode, selectedSlotId } = params
  if (availabilityKind === "paused") {
    return true
  }
  if (availabilityKind === "closed") {
    const allowsClosedPreOrder =
      timingMode === "SCHEDULED_SLOTS_ONLY" ||
      (timingMode === "HYBRID_IMMEDIATE_AND_SLOTS" && selectedSlotId !== null)
    return !allowsClosedPreOrder
  }
  return false
}

export interface FulfilmentSlotPickerProps {
  timingMode?: FulfilmentTimingMode | null
  slots: readonly FulfilmentSlotDto[]
  loading: boolean
  error: string | null
  selectedSlotId: string | null
  onSelectSlot: (slotId: string | null) => void
}

function formatCutoff(iso: string): string {
  try {
    const dt = new Date(iso)
    if (Number.isNaN(dt.getTime())) return iso
    return dt.toLocaleString("en-GB", {
      day: "numeric",
      month: "short",
      hour: "2-digit",
      minute: "2-digit",
    })
  } catch {
    return iso
  }
}

export function FulfilmentSlotPicker({
  timingMode,
  slots,
  loading,
  error,
  selectedSlotId,
  onSelectSlot,
}: FulfilmentSlotPickerProps) {
  if (!timingMode || timingMode === "IMMEDIATE_ONLY") {
    return null
  }

  const isRequired = timingMode === "SCHEDULED_SLOTS_ONLY"
  const isHybrid = timingMode === "HYBRID_IMMEDIATE_AND_SLOTS"

  return (
    <section
      aria-labelledby="fulfilment-slot-picker-heading"
      data-testid="fulfilment-slot-picker"
      className="space-y-4 border-b border-stone-100 pb-6"
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3
            id="fulfilment-slot-picker-heading"
            className="flex items-center gap-2 text-lg font-bold text-stone-900"
          >
            <CalendarClock className="h-5 w-5 text-amber-700" aria-hidden="true" />
            Fulfilment Window
          </h3>
          <p className="mt-0.5 text-xs text-stone-500">
            {isRequired
              ? "Choose a pre-order pickup or delivery window before placing your order."
              : "Order for immediate preparation, or reserve a scheduled window ahead of time."}
          </p>
        </div>
        {isRequired && (
          <span
            data-testid="slot-picker-required-badge"
            className="inline-flex items-center rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-semibold text-amber-900"
          >
            Required
          </span>
        )}
      </div>

      {loading ? (
        <div
          role="status"
          data-testid="fulfilment-slot-picker-loading"
          className="rounded-xl border border-stone-200 bg-stone-50 px-4 py-4 text-sm text-stone-600"
        >
          Loading available fulfilment windows&hellip;
        </div>
      ) : error ? (
        <div
          role="alert"
          data-testid="fulfilment-slot-picker-error"
          className="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-800"
        >
          <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden="true" />
          <span>{error}</span>
        </div>
      ) : (
        <div
          role="radiogroup"
          aria-labelledby="fulfilment-slot-picker-heading"
          className="grid grid-cols-1 gap-3 sm:grid-cols-2"
        >
          {isHybrid && (
            <button
              type="button"
              role="radio"
              aria-checked={selectedSlotId === null}
              data-testid="fulfilment-slot-immediate"
              onClick={() => onSelectSlot(null)}
              className={`flex flex-col items-start justify-between rounded-xl border-2 p-4 text-left transition-all ${
                selectedSlotId === null
                  ? "border-amber-600 bg-amber-50/70 text-stone-900 shadow-sm"
                  : "border-stone-200 bg-white text-stone-700 hover:border-stone-300"
              }`}
            >
              <div className="flex w-full items-center justify-between gap-2">
                <span className="flex items-center gap-1.5 text-sm font-bold">
                  <Sparkles className="h-4 w-4 text-amber-600" aria-hidden="true" />
                  Order for now (ASAP)
                </span>
                {selectedSlotId === null && (
                  <CheckCircle2 className="h-4 w-4 text-amber-700" aria-hidden="true" />
                )}
              </div>
              <span className="mt-1 text-xs text-stone-500">
                Prepared as soon as the kitchen accepts your order
              </span>
            </button>
          )}

          {slots.length === 0 ? (
            <div
              data-testid="fulfilment-slot-picker-empty"
              className="col-span-full rounded-xl border border-dashed border-stone-300 bg-stone-50 px-4 py-5 text-center text-sm text-stone-600"
            >
              No scheduled fulfilment windows are currently open for this shop.
            </div>
          ) : (
            slots.map((slot) => {
              const isClosed = !slot.active
              const isSoldOut =
                slot.active && slot.remainingOrders !== null && slot.remainingOrders <= 0
              const isUnavailable = isClosed || isSoldOut
              const isSelected = selectedSlotId === slot.id
              const isLowCapacity =
                !isUnavailable && slot.remainingOrders !== null && slot.remainingOrders <= 5

              return (
                <button
                  key={slot.id}
                  type="button"
                  role="radio"
                  aria-checked={isSelected}
                  disabled={isUnavailable}
                  data-testid={`fulfilment-slot-option-${slot.id}`}
                  onClick={() => {
                    if (!isUnavailable) onSelectSlot(slot.id)
                  }}
                  className={`flex flex-col items-start justify-between rounded-xl border-2 p-4 text-left transition-all ${
                    isUnavailable
                      ? "cursor-not-allowed border-stone-200 bg-stone-100/70 text-stone-400 opacity-75"
                      : isSelected
                        ? "border-amber-600 bg-amber-50/70 text-stone-900 shadow-sm"
                        : "border-stone-200 bg-white text-stone-800 hover:border-stone-300"
                  }`}
                >
                  <div className="flex w-full items-start justify-between gap-2">
                    <div>
                      <div className="text-sm font-bold">{slot.windowLabel}</div>
                      <div className="mt-0.5 text-xs text-stone-500">
                        {slot.serviceDate} &middot; {slot.windowStart}&ndash;{slot.windowEnd}
                      </div>
                    </div>
                    {isSelected && !isUnavailable && (
                      <CheckCircle2
                        className="h-4 w-4 flex-shrink-0 text-amber-700"
                        aria-hidden="true"
                      />
                    )}
                  </div>

                  <div className="mt-3 flex w-full flex-wrap items-center justify-between gap-2">
                    <span
                      data-testid={`slot-capacity-${slot.id}`}
                      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${
                        isClosed
                          ? "bg-stone-200 text-stone-700"
                          : isUnavailable
                            ? "bg-red-100 text-red-800"
                            : isLowCapacity
                              ? "bg-amber-100 text-amber-900"
                              : "bg-emerald-100 text-emerald-900"
                      }`}
                    >
                      {isClosed
                        ? "Closed"
                        : isUnavailable
                          ? "Sold out"
                          : isLowCapacity
                            ? `Only ${slot.remainingOrders} left`
                            : slot.remainingOrders !== null && slot.maxOrders !== null
                              ? `${slot.remainingOrders} of ${slot.maxOrders} available`
                              : "Available"}
                    </span>

                    <span className="flex items-center gap-1 text-[11px] text-stone-500">
                      <Clock className="h-3 w-3" aria-hidden="true" />
                      Cutoff {formatCutoff(slot.cutoffAt)}
                    </span>
                  </div>
                </button>
              )
            })
          )}
        </div>
      )}
    </section>
  )
}
