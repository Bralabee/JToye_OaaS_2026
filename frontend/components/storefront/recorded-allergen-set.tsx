"use client"

import { AlertTriangle, AlertCircle } from "lucide-react"

import {
  ALLERGEN_PANEL_EMPTY_HEADING_COPY,
  ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY,
  allergenFlagCopy,
} from "@/components/storefront/order-allergen-panel"
import type { OrderAllergenFlag } from "@/types/api"
import { formatUkClockTime } from "@/lib/uk-datetime"

/**
 * "What was recorded on your order" (Phase 31.1-15, D-08; Phase 31 D-03).
 *
 * Shared by the checkout confirmation, the card payment step, and (31.1-18) the per-shop order
 * page and /track; My Orders quotes {@link acknowledgementStatement} for its one-line form. It renders the RESPONSE — the order's record — and computes nothing: a client-side
 * aggregate here could disagree with the kitchen ticket about a safety-relevant set, which is the
 * thing V63's write-time snapshot exists to prevent.
 *
 * THREE STATEMENTS, NEVER MERGED:
 *   acknowledged — what the customer ticked to confirm they had read (orders.allergen_ack_mask, V69);
 *   recorded     — the order-line snapshot union the kitchen works from (V63);
 *   flags        — ADVISORY "Check" lines beside the declaration, never inside either set.
 *
 * And for each set THREE STATES: `null` (not recorded), `[]` (declared none of the 14), a list.
 * `null` is never rendered as "none": that would put a claim in the kitchen's mouth it never made.
 *
 * D-01 still holds: there is deliberately no customer, profile or restriction prop.
 *
 * The copy is exported so tests and future surfaces quote ONE source. The recorded set reuses the
 * panel's declared-none and not-recorded headings, so the checkout and the record say the same
 * thing about the same state.
 */

/** Title of the block. */
export const RECORDED_ALLERGEN_SET_TITLE_COPY = "Allergen record for this order"

/** Lead-in for the set the customer acknowledged at checkout. */
export const RECORDED_ACK_HEADING_COPY = "You confirmed you had read:"

/** Lead-in for the set recorded on the order lines (what the kitchen works from). */
export const RECORDED_SET_HEADING_COPY = "Recorded on your order:"

/** The acknowledgement is NOT RECORDED (a vendor-entered or pre-acknowledgement order). Never "none". */
export const RECORDED_ACK_NOT_RECORDED_COPY = "No allergen acknowledgement was recorded for this order."

/** The customer acknowledged a basket whose kitchen declared none of the 14. A statement, not an absence. */
export const RECORDED_ACK_NONE_COPY =
  "You confirmed you had read that the kitchen declared none of the 14 regulated allergens for these items."

/**
 * The shop placed the order (placedVia VENDOR, D-07): no customer acknowledgement exists, and the
 * reason is stated. Different from NOT RECORDED (a pre-acknowledgement order) and from "none".
 */
export const VENDOR_PLACED_COPY =
  "This order was placed by the shop for you, so no allergen confirmation was recorded."

/** The recorded set is not part of this response (the history list never loads order lines). */
export const RECORDED_SET_NOT_LOADED_COPY = "not loaded with your order list."

/** Action that loads the single-order tracking response, which carries the recorded set. */
export const RECORDED_SET_SHOW_COPY = "Show what the kitchen recorded"

/** D-15: the customer sent an allergy note with the order. The TEXT is never shown here. */
export function ALLERGY_NOTE_SENT_COPY(shopName: string): string {
  return `Your allergy note was sent to ${shopName}.`
}

/** D-15: the shop marked the note read. `time` comes from {@link formatAllergyNoteReadAt}. */
export function ALLERGY_NOTE_READ_COPY(time: string): string {
  return `Read by the shop at ${time}.`
}

/** D-15: sent, not yet marked read. */
export const ALLERGY_NOTE_UNREAD_COPY = "The shop has not marked it as read yet."

/** My Orders: lead-in for the acknowledged set on each order row. */
export const MY_ORDERS_ACK_HEADING_COPY = "Allergens you confirmed:"

/** My Orders: the per-row link to the tracking page, where the full record is shown. */
export const MY_ORDERS_FULL_RECORD_LINK_COPY = "Full allergen record"

/**
 * When the shop read the note, en-GB in Europe/London: "18:05", or "18:05 on 4 Oct" when that is
 * not today IN LONDON. The zone is explicit, never the browser's: a customer abroad, or a device
 * left on another zone, must read the same UK time the shop saw (goods P2-CHA-18).
 *
 * 31.1-22: the rule now lives in lib/uk-datetime.ts (formatUkClockTime), so the vendor's kitchen
 * board and this page say the same time by construction, not by two copies agreeing.
 */
export function formatAllergyNoteReadAt(iso: string, now: Date = new Date()): string {
  return formatUkClockTime(iso, now)
}

/**
 * The acknowledgement sentence for an order — ONE source for this block and for My Orders.
 *
 * Four outcomes, never merged:
 *   list         — the names the customer ticked;
 *   none         — they acknowledged a basket that declared none of the 14 (a statement);
 *   vendor       — the shop placed the order, so there was nothing for the customer to tick (D-07);
 *   not-recorded — no acknowledgement and no reason known (a pre-acknowledgement order, D-06).
 * `undefined` (an older backend omitting the field) reads exactly like `null`.
 */
export type AcknowledgementStatement =
  | { kind: "vendor" | "not-recorded" | "none"; text: string }
  | { kind: "list"; names: string[] }

export function acknowledgementStatement(
  acknowledged: string[] | null | undefined,
  placedVia: string | null | undefined
): AcknowledgementStatement {
  if (acknowledged == null) {
    return placedVia === "VENDOR"
      ? { kind: "vendor", text: VENDOR_PLACED_COPY }
      : { kind: "not-recorded", text: RECORDED_ACK_NOT_RECORDED_COPY }
  }
  if (acknowledged.length === 0) return { kind: "none", text: RECORDED_ACK_NONE_COPY }
  return { kind: "list", names: acknowledged }
}

export interface RecordedAllergenSetProps {
  /** null = no acknowledgement recorded. [] = acknowledged a declared-none basket. */
  acknowledged: string[] | null
  /**
   * null = not recorded. [] = the order lines declared none of the 14. `undefined` = this response
   * does not carry the set (the history list never loads order lines): rendered as NOT LOADED,
   * which is neither "not recorded" nor "none".
   */
  recorded: string[] | null | undefined
  /** Advisory only. Never OR-ed into either set. null and [] both render no line. */
  flags: OrderAllergenFlag[] | null
  /** STOREFRONT | VENDOR | null (31.1-09). VENDOR replaces the acknowledgement line with its reason. */
  placedVia?: string | null
  /** Offered beside a NOT LOADED recorded set: loads the single-order tracking response. */
  onShowRecorded?: () => void
  /** True while that load is in flight. */
  showRecordedPending?: boolean
  /** D-15: the customer sent an allergy note. Its text is never a prop of this block. */
  allergyNoteProvided?: boolean | null
  /** D-15: ISO instant the shop marked the note read; null until then. */
  allergyNoteAcknowledgedAt?: string | null
  /** The shop the note went to, named in the "sent" line. */
  shopName?: string
  /** Distinguishes two blocks on one page; the default suits the single-order surfaces. */
  idPrefix?: string
  className?: string
}

export function RecordedAllergenSet({
  acknowledged,
  recorded,
  flags,
  placedVia = null,
  onShowRecorded,
  showRecordedPending = false,
  allergyNoteProvided = null,
  allergyNoteAcknowledgedAt = null,
  shopName,
  idPrefix = "recorded-allergens",
  className = "",
}: RecordedAllergenSetProps) {
  const titleId = `${idPrefix}-title`
  const flagLines = flags ?? []
  const ack = acknowledgementStatement(acknowledged, placedVia)

  return (
    <section
      data-testid="recorded-allergen-set"
      aria-labelledby={titleId}
      className={`rounded-xl border border-amber-600 bg-amber-50 p-4 ${className}`}
    >
      <div className="flex items-start gap-2">
        <AlertTriangle
          data-testid="recorded-allergen-icon"
          className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-800"
          aria-hidden="true"
        />
        <h2 id={titleId} className="text-sm font-semibold text-amber-800">
          {RECORDED_ALLERGEN_SET_TITLE_COPY}
        </h2>
      </div>

      {/* Explicit branches: `[]` is truthy and `null` is not, so a `?.length` shortcut would merge
          the states this block exists to keep apart. */}
      <p data-testid="recorded-ack" className="mt-2 text-sm text-amber-700">
        {ack.kind === "list" ? (
          <>
            {RECORDED_ACK_HEADING_COPY}{" "}
            <span className="font-semibold text-amber-800">{ack.names.join(", ")}</span>
          </>
        ) : (
          ack.text
        )}
      </p>

      <p data-testid="recorded-set" className="mt-1.5 text-sm text-amber-700">
        {RECORDED_SET_HEADING_COPY}{" "}
        {recorded === undefined ? (
          <>
            {RECORDED_SET_NOT_LOADED_COPY}
            {onShowRecorded && (
              <>
                {" "}
                <button
                  type="button"
                  onClick={onShowRecorded}
                  disabled={showRecordedPending}
                  aria-busy={showRecordedPending || undefined}
                  className="font-semibold text-amber-800 underline underline-offset-2 transition-colors duration-150 ease-out disabled:opacity-60 [@media(hover:hover)_and_(pointer:fine)]:hover:text-amber-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-600 focus-visible:ring-offset-1 rounded-sm"
                >
                  {RECORDED_SET_SHOW_COPY}
                </button>
              </>
            )}
          </>
        ) : (
          <span className="font-semibold text-amber-800">
            {recorded === null
              ? ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY
              : recorded.length === 0
                ? ALLERGEN_PANEL_EMPTY_HEADING_COPY
                : recorded.join(", ")}
          </span>
        )}
      </p>

      {flagLines.length > 0 && (
        <ul className="mt-3 space-y-2">
          {flagLines.map((flag) => (
            <li
              key={`${flag.productName}-${flag.allergenBit}`}
              data-testid="recorded-allergen-flag"
              className="flex items-start gap-2 rounded-lg border border-amber-600 bg-white p-2.5 text-sm text-amber-800"
            >
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden="true" />
              <span>{allergenFlagCopy(flag)}</span>
            </li>
          ))}
        </ul>
      )}

      {/* D-15: that the note arrived and whether the shop has read it. Never its text — the
          tracking response does not carry it (T-31.1-67), and this block takes no such prop. */}
      {allergyNoteProvided === true && (
        <div
          data-testid="allergy-note-status"
          className="mt-3 border-t border-amber-600/40 pt-3 text-sm text-amber-700"
        >
          <p>{ALLERGY_NOTE_SENT_COPY(shopName ?? "the shop")}</p>
          <p className="mt-0.5 font-semibold text-amber-800">
            {allergyNoteAcknowledgedAt
              ? ALLERGY_NOTE_READ_COPY(formatAllergyNoteReadAt(allergyNoteAcknowledgedAt))
              : ALLERGY_NOTE_UNREAD_COPY}
          </p>
        </div>
      )}
    </section>
  )
}
