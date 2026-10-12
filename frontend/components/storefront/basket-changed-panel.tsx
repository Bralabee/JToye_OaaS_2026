"use client"

import * as React from "react"
import { RefreshCcw } from "lucide-react"

import { Button } from "@/components/ui/button"
import type { BasketChange } from "@/types/storefront"

/**
 * C1 — "Your basket has changed" (Phase 37-34, UI-SPEC § C1 and § Copywriting C1-C4; D-16;
 * RWO-007 / UXT-007).
 *
 * The server (37-33) compares the basket the customer SAW with the one it reads at submit and refuses
 * any difference with ONE typed 409 `basket-changed` whose `changes[]` names every change. This panel
 * renders that list, one row per change with that kind's exact sentence — "Suya · £9.00 → £14.00" —
 * and ends with "Accept changes". Until it is pressed the page refuses Place order (refuse-not-disable,
 * the 31.1 allergen-gate pattern: a `role="alert"` line and focus on the button); after it, the panel
 * collapses to a one-line note and the Place order label shows the new total.
 *
 * PURELY PRESENTATIONAL. The page owns the basket rewrite (cart-provider `applyServerChanges`, which
 * leaves the ownership stamp alone — R-16), the Idempotency-Key policy (the resubmit reuses the SAME
 * key: one order from any tap sequence, goods P2-CHA-12/13, P2-RAV-20) and the allergen re-render
 * when the same 409 carries the 31.1 properties; this component only says what happened.
 *
 * A REMOVED entry carries NO name on the wire, by design (37-33: another shop's product is not
 * disclosed). The basket the customer saw has the name, so `nameFor` reads it from there; the
 * server's name wins when it sends one, and "An item" is the last resort — never "undefined".
 *
 * THE COPY IS THE UI-SPEC'S CONTRACT (§ Copywriting C1-C4), exported as named constants so the
 * page, the tests and the e2e spec quote ONE source. Do not paraphrase it in passing.
 */

export const BASKET_CHANGED_HEADING_COPY = "Your basket has changed since you opened it"
export const BASKET_CHANGED_ACCEPT_LABEL = "Accept changes"
/** Rendered `role="alert"` beside the panel when Place order is pressed before "Accept changes". */
export const BASKET_CHANGED_REFUSAL_COPY = "Review the changes to your basket, then press Accept changes."
/** What the panel collapses to once the changes are accepted. */
export const BASKET_UPDATED_NOTE_COPY = "Basket updated. Check the new total before you place your order."
/**
 * The error-slot copy for the 422 `basket-confirmation-required` (a cached pre-37-C page sent no
 * expected prices) AND for a malformed `basket-changed` body (no readable `changes[]`): nothing here
 * can be rendered or applied, and the basket is left exactly as it was (UI-SPEC § C1 "empty",
 * the planner's assumption; the `readStaleAllergenProblem` null precedent).
 */
export const BASKET_CONFIRMATION_REQUIRED_COPY =
  "Something went wrong preparing your order. Refresh the page and try again. Your basket is saved."
/** Shown in the panel when the same 409 carries the 31.1 allergen properties. */
export const ALLERGENS_ALSO_CHANGED_COPY =
  "The allergens in your order have changed too. Check them below and tick the box again."
/** The name of a line neither the server nor the basket can name. */
export const BASKET_CHANGE_FALLBACK_NAME = "An item"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

function isPennies(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
}

/** One rendered row: the sentence, plus the parts the markup emphasises or voices differently. */
export interface BasketChangeRow {
  key: string
  /** The sentence as a sighted reader sees it. */
  text: string
  /** The item name the sentence opens with (emphasised), when it opens with one. */
  name?: string
  /** PRICE_CHANGED only: the visual "£was → £now" and the sentence a screen reader hears instead. */
  prices?: { visual: string; srOnly: string }
}

/**
 * The exact sentence for one server change (UI-SPEC § Copywriting C1-C4), or null for an entry
 * this panel cannot say anything true about (an unknown kind, a price change without both prices).
 * The page's reader already refuses such bodies; this is the second guard, so a row is never
 * rendered with "£NaN" in it.
 */
export function basketChangeCopy(
  change: BasketChange,
  nameFor: (productId: string | undefined) => string | null
): BasketChangeRow | null {
  const serverName = typeof change.name === "string" ? change.name.trim() : ""
  const name = serverName || nameFor(change.productId) || BASKET_CHANGE_FALLBACK_NAME
  const key = `${change.kind}:${change.productId ?? ""}`
  switch (change.kind) {
    case "PRICE_CHANGED": {
      if (!isPennies(change.expected) || !isPennies(change.current)) return null
      const was = formatPrice(change.expected)
      const now = formatPrice(change.current)
      const visual = `${was} → ${now}`
      return { key, name, text: `${name} · ${visual}`, prices: { visual, srOnly: `was ${was}, now ${now}` } }
    }
    case "UNAVAILABLE":
      return { key, name, text: `${name} · no longer available, removed from your basket` }
    case "SOLD_OUT": {
      const available = isPennies(change.available) ? change.available : 0
      if (available <= 0) return { key, name, text: `${name} · sold out, removed from your basket` }
      const from = isPennies(change.requested) ? ` from ${change.requested}` : ""
      return {
        key,
        name,
        text: `${name} · only ${available} left, we've changed your quantity${from} to ${available}`,
      }
    }
    case "REMOVED":
      return { key, name, text: `${name} · no longer on this shop's menu, removed from your basket` }
    case "BELOW_MINIMUM": {
      if (isPennies(change.minimum) && isPennies(change.subtotal)) {
        const shortfall = Math.max(0, change.minimum - change.subtotal)
        return {
          key,
          text: `Your basket is now below this shop's ${formatPrice(change.minimum)} minimum. Add ${formatPrice(shortfall)} more to order.`,
        }
      }
      const detail = typeof change.detail === "string" ? change.detail.trim() : ""
      return detail ? { key, text: detail } : null
    }
    case "TOTAL_CHANGED": {
      if (!isPennies(change.expected) || !isPennies(change.current)) return null
      return { key, text: `Total was ${formatPrice(change.expected)}, now ${formatPrice(change.current)}` }
    }
    default:
      return null
  }
}

export interface BasketChangedPanelProps {
  /** The server's `changes[]`, in the server's order (per line in basket order, then the basket-level entries). */
  changes: BasketChange[]
  /** The basket AS THE CUSTOMER SAW IT, by product id: the only place a REMOVED entry's name can come from. */
  nameFor: (productId: string | undefined) => string | null
  /** The same 409 carried the 31.1 allergen properties: the allergen panel below is re-rendered unticked. */
  allergensChanged: boolean
  /** "Accept changes" has been pressed: collapse to the note. */
  accepted: boolean
  onAccept: () => void
  /** Place order was pressed before "Accept changes": announce the refusal beside the panel. */
  refused: boolean
  /** Lets the page move focus to "Accept changes" on that refusal. */
  acceptRef?: React.Ref<HTMLButtonElement>
  /** Stable id for the refusal line. */
  refusalId?: string
}

export function BasketChangedPanel({
  changes,
  nameFor,
  allergensChanged,
  accepted,
  onAccept,
  refused,
  acceptRef,
  refusalId = "basket-changed-refusal",
}: BasketChangedPanelProps) {
  const headingId = React.useId()
  const headingRef = React.useRef<HTMLHeadingElement>(null)

  // Focus moves to the heading when the panel appears (UI-SPEC § C1): the customer is told WHAT
  // changed before they are asked to accept it. Mount only — the page keys the panel on each 409, so
  // a second refusal mounts a fresh panel and moves focus again. Guarded like every other focus
  // move on the checkout (product-detail-modal.tsx:117).
  React.useEffect(() => {
    const el = headingRef.current
    if (el && el.isConnected) el.focus()
  }, [])

  if (accepted) {
    return (
      <p
        data-testid="basket-updated-note"
        role="status"
        className="rounded-xl border border-orange-700 bg-orange-50 p-3 text-sm text-orange-800"
      >
        {BASKET_UPDATED_NOTE_COPY}
      </p>
    )
  }

  const rows = changes
    .map((change) => basketChangeCopy(change, nameFor))
    .filter((row): row is BasketChangeRow => row !== null)

  return (
    <>
      <section
        data-testid="basket-changed-panel"
        role="alert"
        aria-labelledby={headingId}
        className="rounded-xl border border-orange-700 bg-orange-50 p-4"
      >
        <div className="flex items-start gap-2">
          <RefreshCcw className="mt-0.5 h-5 w-5 flex-shrink-0 text-orange-800" aria-hidden="true" />
          <h2
            id={headingId}
            ref={headingRef}
            tabIndex={-1}
            className="rounded text-sm font-semibold text-orange-800 outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            {BASKET_CHANGED_HEADING_COPY}
          </h2>
        </div>

        {/* One row per change. `break-words` and no clipping class: a long dish name wraps at 375px
            and at 360px with the root at 130% (UI-SPEC § C1 backstops); prices are tabular so a
            column of rows lines up. */}
        <ul data-testid="basket-change-list" className="mt-2 space-y-1.5 text-sm text-orange-800">
          {rows.map((row, i) => (
            <li key={`${row.key}:${i}`} data-testid="basket-change-row" className="break-words">
              {row.prices ? (
                <>
                  <span className="font-semibold">{row.name}</span>
                  {" · "}
                  {/* The arrow is visual. Assistive tech hears the sentence in its place. */}
                  <span aria-hidden="true" className="tabular-nums">
                    {row.prices.visual}
                  </span>
                  <span className="sr-only">{row.prices.srOnly}</span>
                </>
              ) : row.name ? (
                <>
                  <span className="font-semibold">{row.name}</span>
                  {row.text.slice(row.name.length)}
                </>
              ) : (
                <span className="tabular-nums">{row.text}</span>
              )}
            </li>
          ))}
        </ul>

        {allergensChanged && (
          <p data-testid="basket-allergens-changed" className="mt-2 text-sm font-semibold text-orange-800">
            {ALLERGENS_ALSO_CHANGED_COPY}
          </p>
        )}

        <Button
          type="button"
          variant="outline"
          ref={acceptRef}
          onClick={onAccept}
          className="mt-3 h-11 w-full border-2 border-oxblood bg-white text-oxblood hover:bg-orange-100 hover:text-oxblood"
        >
          {BASKET_CHANGED_ACCEPT_LABEL}
        </Button>
      </section>

      {/* Rendered ONLY on a refusal, beside the panel: a permanently mounted alert is announced on
          mount and trains users to ignore the one that matters (the OrderAllergenPanel rule). */}
      {refused && (
        <p id={refusalId} role="alert" className="text-sm font-semibold text-red-700">
          {BASKET_CHANGED_REFUSAL_COPY}
        </p>
      )}
    </>
  )
}
