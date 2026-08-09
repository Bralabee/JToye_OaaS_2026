/**
 * The single parser for `X-Search-Interpretation`, and the single place a
 * proximity heading can be produced (issue 619, CUST-01).
 *
 * ── WHY A PARSER AND NOT A REGEX ─────────────────────────────────────────────
 *
 * `33-08` answers `GET /public/shops?q=SE22` with kitchens near the SE22
 * district centroid, and states how it read `q` in a response header. The
 * storefront's job is to REPEAT that statement, never to make one. A client-side
 * "does this look like a postcode?" test would let the page claim a proximity
 * reading on a response where the server did a plain text match — which is the
 * "row lying about itself" failure class phase 33 exists to close, and it is
 * recorded here as threat T-33-09-01.
 *
 * So there is no UK-postcode regex in this module, and none under
 * `app/shop/` outside the pre-existing checkout validator. CA-E asserts that
 * with a positive control proving the search can find one when it is there.
 *
 * ── EVERY UNPARSEABLE INPUT DEGRADES TO `text` ───────────────────────────────
 *
 * Absence, an empty value, an unknown kind, a missing or non-finite `radiusKm`,
 * an unknown `precision`, a key outside the server's own charset, a control
 * character: all of them return `{kind:"text"}`. That is the fail-safe
 * direction, and it is the whole safety argument — the UI can only ever FAIL TO
 * CLAIM proximity, never invent one. An incomplete disclosure is not a
 * disclosure.
 *
 * ── THE GRAMMAR, AS SHIPPED BY 33-08 (not as its plan specified) ─────────────
 *
 *   X-Search-Interpretation: text
 *   X-Search-Interpretation: proximity; postcode=SE22; precision=district; radiusKm=5.0
 *   X-Search-Interpretation: proximity; postcode=SE220AA; precision=unit; radiusKm=5.0
 *
 * Single space after each `;`; `precision` lowercased; `postcode` space-stripped
 * and upper-cased; `radiusKm` is `String.valueOf(double)`, so the platform
 * default renders `5.0` and never `5`. Emitted on `?q=` responses only — absent
 * from the plain listing and from the `lat`/`lon` distance path, because with no
 * `q` there is no question to answer. Whitespace is trimmed here anyway rather
 * than assumed, so a future server that pads differently is still understood.
 */

import { formatMiles } from "@/lib/distance"

/** The header name, lower-cased — axios lower-cases response header keys. */
export const SEARCH_INTERPRETATION_HEADER = "x-search-interpretation"

/**
 * The server's statement about how it read `q`.
 *
 * `radiusKm` is the radius the query ACTUALLY applied, in the wire's metric
 * unit. Every customer-facing rendering of it goes through `formatMiles`.
 */
export type SearchInterpretation =
  | { kind: "text" }
  | {
      kind: "proximity"
      /** The server's normalised key: space-stripped, upper-case (`SE22`, `SE155BS`). */
      postcode: string
      precision: "unit" | "district"
      radiusKm: number
    }

/** The default, and the destination of every degradation. */
export const TEXT_INTERPRETATION: SearchInterpretation = { kind: "text" }

/**
 * The generic exclusion disclosure (decision D-D).
 *
 * `near-you-row.tsx` COUNTS its exclusions. This surface cannot: the counts need
 * a second, unfiltered request, and the standing web-performance criterion
 * forbids adding a round trip to a public route. So the claim is generic and
 * strictly weaker — recorded as weaker rather than presented as equivalent — but
 * it still obeys the binding half of the rule: exclusions are disclosed, never
 * silently dropped, and nothing is derived by subtraction.
 */
export const PROXIMITY_EXCLUSION_NOTE =
  "Kitchens we cannot place, and any further away, are not shown."

/** What the summary line says, and how the island should render it. */
export type SearchSummary =
  | {
      kind: "text"
      /** Everything before the quoted term. */
      lead: string
      /** The term the customer typed, rendered emphasised and quoted. */
      term: string
      /** The whole line as one string, for a caller that wants no markup. */
      text: string
    }
  | { kind: "proximity"; text: string }

// RED STUB — replaced in the GREEN commit. Present so the tests fail on real
// assertions rather than on a missing module.
export function parseSearchInterpretation(
  _raw: string | null | undefined
): SearchInterpretation {
  return TEXT_INTERPRETATION
}

// RED STUB — replaced in the GREEN commit.
export function formatPostcodeForDisplay(
  key: string,
  _precision: "unit" | "district"
): string {
  return key
}

// RED STUB — replaced in the GREEN commit.
export function searchSummary(
  _interpretation: SearchInterpretation,
  totalElements: number,
  query: string
): SearchSummary {
  const term = query.trim()
  const lead = totalElements === 0 ? "No kitchens match " : `${totalElements} kitchens for `
  return { kind: "text", lead, term, text: `${lead}“${term}”` }
}

// `formatMiles` is imported so the GREEN body has one conversion and one only.
void formatMiles
