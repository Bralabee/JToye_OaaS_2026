"use client"

import * as React from "react"
import { AlertTriangle, AlertCircle } from "lucide-react"

import { Checkbox } from "@/components/ui/checkbox"
import type { OrderAllergenFlag } from "@/types/api"

/**
 * S3 — the pre-submit allergen set and its acknowledgement (Phase 31-14, LGL-03 / D-01..D-03).
 *
 * THIS COMPONENT IS PURELY PRESENTATIONAL AND AGGREGATES NOTHING. It renders the set it is
 * handed. A client-side aggregate computed *inside* the panel would let this surface and the
 * kitchen display disagree about a safety-relevant set, and the whole point of V63's write-time
 * snapshot is that they cannot.
 *
 * THE THREE STATES, which must never be conflated:
 *
 *   allergenNames === null   NOT RECORDED — we do not have the information.
 *   allergenNames === []     The vendor declared NONE of the 14 regulated allergens.
 *   allergenNames.length > 0 The declared set.
 *
 * `null` and `[]` are different STATEMENTS, not two spellings of "nothing". Showing the
 * "no allergens declared" copy for a null would put a claim in the vendor's mouth that the vendor
 * never made — and that claim is the direction that injures someone.
 *
 * THE COPY BELOW IS LEGALLY OPERATIVE. A regulator may read it. It is exported as named constants
 * so the checkout, the tests and `docs/legal/article-9-allergen-basis.md` all quote ONE source;
 * do not paraphrase it, and do not "improve" it in passing.
 *
 * D-01 IS A HARD CONSTRAINT HERE: this component takes no customer, no profile and no allergen
 * restriction mask, and there is deliberately no prop through which one could be passed. The
 * customer's stored allergen profile was removed from this surface on 2026-07-30 as special
 * category data with no Article 9 condition.
 */

/** Heading when the order has a declared allergen set. */
export const ALLERGEN_PANEL_HEADING_COPY = "Allergens in this order"

/** Heading when the vendor declared none of the 14. NOT the same as "not recorded". */
export const ALLERGEN_PANEL_EMPTY_HEADING_COPY = "No allergens declared for this order"

/** Body for the "declared none" state — explicitly denies "allergen-free". */
export const ALLERGEN_PANEL_EMPTY_BODY_COPY =
  "The kitchen has not declared any of the 14 regulated allergens for these items. That is not the same as allergen-free — if you have a serious allergy, tell the kitchen before you order."

/**
 * Heading for the NOT RECORDED state.
 *
 * AUTHORED, NOT CONTRACTED: the UI-SPEC supplies verbatim copy for the declared and the
 * declared-none states but none for this third state, which 31-10's wire contract makes
 * reachable (`null` on every allergen field together). Written in the register of the
 * contracted strings and recorded in 31-14-SUMMARY.md for the phase owner to ratify.
 */
export const ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY =
  "Allergen information not recorded for this order"

/** Body for the NOT RECORDED state. Authored — see the heading note above. */
export const ALLERGEN_PANEL_NOT_RECORDED_BODY_COPY =
  "We do not have the allergen information for these items. That is not the same as allergen-free — ask the kitchen before you order."

/** The acknowledgement the customer is bound by. */
export const ALLERGEN_ACK_LABEL_COPY = "I have read the allergen information for this order."

/** D-01 and D-02 stated to the person they affect, in one sentence. It stays. */
export const ALLERGEN_PANEL_SUBLINE_COPY =
  "We do not store your allergies and we cannot check this order against them."

/** Rendered in a `role="alert"` region when a submit is refused. */
export const ALLERGEN_ACK_ERROR_COPY =
  "Confirm you have read the allergen information before placing this order."

/** The intro line, which attributes the declaration to the kitchen that made it. */
export function allergenPanelIntroCopy(vendorName: string): string {
  return `These items are prepared by ${vendorName}. Based on what the kitchen has declared, this order contains:`
}

/**
 * One advisory reconciliation line. Never rendered as if it were a declared allergen — it is a
 * text heuristic over the vendor's emphasised ingredients, carried BESIDE the declaration.
 */
export function allergenFlagCopy(flag: OrderAllergenFlag): string {
  return `Check — ${flag.productName}: the ingredients list mentions ${flag.allergenName}, which the kitchen has not declared for this item. Ask the kitchen before you order.`
}

export interface OrderAllergenPanelProps {
  /** The trading name of the kitchen that made the declaration. */
  vendorName: string
  /** null = NOT RECORDED. [] = the vendor declared none of the 14. Never collapse one into the other. */
  allergenNames: string[] | null
  /** Advisory only. Never OR-ed into `allergenNames`. */
  allergenFlags: OrderAllergenFlag[] | null
  acknowledged: boolean
  onAcknowledgedChange: (next: boolean) => void
  /** Set by the parent when a submit was refused for want of the acknowledgement. */
  errored?: boolean
  /** Stable id shared by the alert region and the checkbox's aria-describedby. */
  errorId?: string
  checkboxId?: string
  /** Lets the parent move focus to the control that refused. */
  checkboxRef?: React.Ref<HTMLButtonElement>
}

export function OrderAllergenPanel({
  vendorName,
  allergenNames,
  allergenFlags,
  acknowledged,
  onAcknowledgedChange,
  errored = false,
  errorId = "allergen-ack-error",
  checkboxId = "allergen-ack",
  checkboxRef,
}: OrderAllergenPanelProps) {
  const headingId = `${checkboxId}-heading`
  const labelId = `${checkboxId}-label`

  // Explicit three-way branch rather than a truthiness test: `[]` is truthy and `null` is not,
  // so a `allergenNames?.length` shortcut would silently merge the two states this panel exists
  // to keep apart.
  const notRecorded = allergenNames === null
  const declaredNone = allergenNames !== null && allergenNames.length === 0
  const declared = allergenNames !== null && allergenNames.length > 0

  const heading = notRecorded
    ? ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY
    : declaredNone
      ? ALLERGEN_PANEL_EMPTY_HEADING_COPY
      : ALLERGEN_PANEL_HEADING_COPY

  const flags = allergenFlags ?? []

  return (
    <section
      data-testid="order-allergen-panel"
      aria-labelledby={headingId}
      className="rounded-xl border border-amber-600 bg-amber-50 p-4"
    >
      <div className="flex items-start gap-2">
        <AlertTriangle className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-800" aria-hidden="true" />
        <h2 id={headingId} className="text-sm font-semibold text-amber-800">
          {heading}
        </h2>
      </div>

      {declared && (
        <>
          <p className="mt-2 text-sm text-amber-700">{allergenPanelIntroCopy(vendorName)}</p>
          <ul className="mt-2 flex flex-wrap gap-1.5">
            {allergenNames.map((name) => (
              <li
                key={name}
                data-testid="allergen-chip"
                className="rounded-full border border-amber-600 bg-white px-2.5 py-1 text-sm font-semibold text-amber-800"
              >
                {name}
              </li>
            ))}
          </ul>
        </>
      )}

      {declaredNone && <p className="mt-2 text-sm text-amber-700">{ALLERGEN_PANEL_EMPTY_BODY_COPY}</p>}

      {notRecorded && (
        <p className="mt-2 text-sm text-amber-700">{ALLERGEN_PANEL_NOT_RECORDED_BODY_COPY}</p>
      )}

      {flags.length > 0 && (
        <ul className="mt-3 space-y-2">
          {flags.map((flag) => (
            <li
              key={`${flag.productName}-${flag.allergenBit}`}
              data-testid="allergen-flag"
              className="flex items-start gap-2 rounded-lg border border-amber-600 bg-white p-2.5 text-sm text-amber-800"
            >
              <AlertCircle
                data-testid="allergen-flag-icon"
                className="mt-0.5 h-4 w-4 flex-shrink-0"
                aria-hidden="true"
              />
              <span>{allergenFlagCopy(flag)}</span>
            </li>
          ))}
        </ul>
      )}

      {/* 44px minimum touch target (min-h-11) around a 24px box, per the UI-SPEC. */}
      <div data-testid="allergen-ack-row" className="mt-3 flex min-h-11 items-center gap-3">
        <Checkbox
          id={checkboxId}
          ref={checkboxRef}
          checked={acknowledged}
          onCheckedChange={(next) => onAcknowledgedChange(next === true)}
          aria-labelledby={labelId}
          aria-invalid={errored ? "true" : undefined}
          aria-describedby={errored ? errorId : undefined}
          className="border-amber-600 focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        />
        <label
          id={labelId}
          htmlFor={checkboxId}
          className="cursor-pointer text-sm font-semibold text-amber-800"
        >
          {ALLERGEN_ACK_LABEL_COPY}
        </label>
      </div>

      <p className="mt-2 text-sm text-amber-700">{ALLERGEN_PANEL_SUBLINE_COPY}</p>

      {/* Rendered ONLY when errored. A permanently-mounted alert region is announced on mount and
          trains users to ignore the one announcement that matters. */}
      {errored && (
        <p id={errorId} role="alert" className="mt-2 text-sm font-semibold text-amber-800">
          {ALLERGEN_ACK_ERROR_COPY}
        </p>
      )}
    </section>
  )
}
