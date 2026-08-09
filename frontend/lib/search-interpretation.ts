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

/**
 * The server's own emission gate, mirrored.
 *
 * `SearchInterpretation.headerValue()` refuses to emit a key that is not
 * `^[A-Z0-9]{2,8}$` (33-08's T-33-08-05, response splitting), so anything else
 * arriving here did not come from a server this client understands. This is a
 * CHARSET AND LENGTH guard, deliberately NOT a UK-postcode shape: it cannot
 * decide whether a string is a postcode and is never used to make that decision
 * — the header is the decision. See CA-E.
 */
const SERVER_KEY = /^[A-Z0-9]{2,8}$/

/** Anything a header value has no business containing. */
const CONTROL_CHARACTERS = /[\u0000-\u001F\u007F]/

/** A full unit key is its outward code plus a 3-character inward code. */
const INWARD_LENGTH = 3

export function parseSearchInterpretation(
  raw: string | null | undefined
): SearchInterpretation {
  if (typeof raw !== "string") return TEXT_INTERPRETATION
  // A control character cannot have survived the server's own gate, so the whole
  // value is refused rather than sanitised — sanitising would keep a claim made
  // by something that is not the server.
  if (CONTROL_CHARACTERS.test(raw)) return TEXT_INTERPRETATION

  const parts = raw
    .split(";")
    .map((part) => part.trim())
    .filter((part) => part.length > 0)

  // `text`, an unknown kind, a differently-cased kind and a value with no kind
  // at all all land here. One return, so a new kind cannot be silently adopted.
  if (parts[0] !== "proximity") return TEXT_INTERPRETATION

  const pairs = new Map<string, string>()
  for (const part of parts.slice(1)) {
    const eq = part.indexOf("=")
    if (eq <= 0) continue
    pairs.set(part.slice(0, eq).trim(), part.slice(eq + 1).trim())
  }

  const postcode = pairs.get("postcode") ?? ""
  if (!SERVER_KEY.test(postcode)) return TEXT_INTERPRETATION

  const precision = pairs.get("precision")
  if (precision !== "unit" && precision !== "district") return TEXT_INTERPRETATION

  // An incomplete disclosure is not a disclosure: without the radius there is no
  // honest way to say what the results were filtered by, so nothing is claimed.
  // `Number("")` is 0, hence the emptiness check before the conversion.
  const radiusRaw = pairs.get("radiusKm")
  if (radiusRaw === undefined || radiusRaw.length === 0) return TEXT_INTERPRETATION
  const radiusKm = Number(radiusRaw)
  if (!Number.isFinite(radiusKm) || radiusKm <= 0) return TEXT_INTERPRETATION

  return { kind: "proximity", postcode, precision, radiusKm }
}

/**
 * The server's space-stripped key, rendered the way a customer wrote it.
 *
 * Formatting only. A district key is printed exactly as it arrived; a unit key
 * has its single space restored before the inward code. A key too short to hold
 * an inward code is returned untouched rather than mangled — the server cannot
 * produce one, and inventing a split would print something nobody typed.
 */
export function formatPostcodeForDisplay(
  key: string,
  precision: "unit" | "district"
): string {
  if (precision !== "unit") return key
  if (key.length <= INWARD_LENGTH + 1) return key
  return `${key.slice(0, key.length - INWARD_LENGTH)} ${key.slice(-INWARD_LENGTH)}`
}

/**
 * The one place the result summary is written, for both readings of `q`.
 *
 * The text branch reproduces the copy this surface has always shown, including
 * the emphasised quoted term — hence the split return rather than a bare string:
 * collapsing it would have silently deleted the emphasis the page already had.
 *
 * The proximity branch quotes the radius through `formatMiles`, from the
 * `radiusKm` the SERVER applied. Never a second literal, and never tidied: 5 km
 * reads "3.1 miles" because 3 miles is 4.83 km, a radius nothing applied. If a
 * rounder number is ever wanted, change the radius and send that.
 */
export function searchSummary(
  interpretation: SearchInterpretation,
  totalElements: number,
  query: string
): SearchSummary {
  const count =
    totalElements === 0
      ? null
      : `${totalElements} ${totalElements === 1 ? "kitchen" : "kitchens"}`

  if (interpretation.kind === "proximity") {
    const where = `within ${formatMiles(interpretation.radiusKm)} of ${formatPostcodeForDisplay(
      interpretation.postcode,
      interpretation.precision
    )}`
    return {
      kind: "proximity",
      text: count === null ? `No kitchens ${where}` : `${count} ${where}`,
    }
  }

  const lead = count === null ? "No kitchens match " : `${count} for `
  const term = query.trim()
  return { kind: "text", lead, term, text: `${lead}“${term}”` }
}
