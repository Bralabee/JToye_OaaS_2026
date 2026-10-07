/**
 * The one source of the allergen phrases the vendor dashboard and the storefront print
 * (31.1-19; #787, #861; D-09, D-16, D-18).
 *
 * Every phrase here is a statement about what a vendor DECLARED, never about what a dish
 * contains or about who may eat it. An empty declaration is reported as an empty declaration.
 *
 * Allergen NAMES are never spelled here: callers pass names that come from the shared catalogue
 * (`ALLERGENS` in types/api.ts, parity-tested against core-java's AllergenCatalog) or from the
 * server's own warning. This module only arranges them into sentences.
 *
 * The strings are pinned verbatim by lib/__tests__/allergen-copy.test.ts. Change one there first.
 */

/** D-18: a declaration with none of the 14 regulated allergens. */
export const NO_ALLERGENS_DECLARED_COPY = "No allergens declared"

/** D-18: the label of the declared set where it is shown as a list (the dish modal). */
export const CONTAINS_LABEL_COPY = "Contains"

/**
 * D-18, storefront: the declared allergens in words ("Contains: Gluten, Milk"), never a count.
 * Returns null for an empty declaration; the caller then states NO_ALLERGENS_DECLARED_COPY.
 */
export function containsCopy(names: readonly string[]): string | null {
  return names.length === 0 ? null : `${CONTAINS_LABEL_COPY}: ${names.join(", ")}`
}

/**
 * Pitfall 6: the server's reconciliation (31.1-06) reads CAPITALS and **double asterisks** as
 * emphasis. The form says so, because the persona typed "butter (MILK)" with no guidance.
 */
export const INGREDIENTS_EMPHASIS_HELP_COPY =
  "Write each allergen in CAPITALS or **double asterisks**, for example: rice, butter (MILK), pepper. We check these against the allergens you tick below."

/** D-16: the may-contain fieldset's helper text. */
export const MAY_CONTAIN_HELP_COPY =
  "Allergens that may get into this dish from your kitchen, shown separately to customers. These are not ingredients."

/** D-09: the vendor's "leave the declaration as it is" action on the save-time warning. */
export const KEEP_AS_IS_COPY = "Keep as it is"

/** An allergen name as the vendor emphasised it in the ingredients ("Milk" -> "MILK"). */
function asEmphasised(name: string): string {
  return name.toLocaleUpperCase("en-GB")
}

function namesLine(names: readonly string[]): string | null {
  if (names.length === 0) return null
  return `Ingredients name: ${names.map(asEmphasised).join(", ")}`
}

/**
 * D-09, storefront: the ingredients name an allergen the declaration omits.
 * Returns null when there is nothing to say, so a caller never prints an empty sentence.
 */
export function undeclaredIngredientCopy(names: readonly string[]): string | null {
  const line = namesLine(names)
  return line === null ? null : `${line} – check with the shop`
}

/** D-09, vendor products list: the same disagreement, said to the vendor who can resolve it. */
export function undeclaredIngredientVendorCopy(names: readonly string[]): string | null {
  const line = namesLine(names)
  return line === null ? null : `${line} – not ticked`
}

/** D-09, vendor form: one line of the save-time warning. */
export function vendorSaveWarningCopy(name: string): string {
  return `The ingredients mention ${name}, but ${name} is not ticked`
}

/** D-09, vendor form: the action that ticks the named allergen and saves again. */
export function tickAllergenCopy(name: string): string {
  return `Tick ${name}`
}

/**
 * D-16: the "may contain" line (cross-contact), the same words the PPDS label prints.
 * Returns null for an empty list: an empty list means print nothing.
 */
export function mayContainCopy(names: readonly string[]): string | null {
  return names.length === 0 ? null : `May contain: ${names.join(", ")}`
}


// ---------------------------------------------------------------------------------------------
// 31.1-22 (#812, D-15): the customer's allergy note on the kitchen board, the printed ticket and
// the vendor order detail, and the shop's acknowledgement of it. The note is the CUSTOMER's words;
// these phrases only frame it and say whether someone in the shop has marked it as read.
// ---------------------------------------------------------------------------------------------

/** The block's label. Uppercase in the string itself, like ALLERGENS on the banner beside it. */
export const ALLERGY_NOTE_LABEL_COPY = "ALLERGY NOTE"

/** The action. It records that a person read the note, so it says exactly that. */
export const ALLERGY_NOTE_ACK_BUTTON_COPY = "Mark allergy note as read"

/** The printed ticket's line before anyone has marked the note read (paper carries no button). */
export const ALLERGY_NOTE_NOT_READ_PRINT_COPY = "NOT YET MARKED AS READ"

/** Who read it, when the viewer is the account that did. */
export const ALLERGY_NOTE_READER_YOU_COPY = "you"

/** Who read it, when it was another account: the stored id is never printed. */
export const ALLERGY_NOTE_READER_STAFF_COPY = "a member of staff"

/** "Read by kim at 18:05"; "Read at 18:05" when nobody can be named. `time` is UK time. */
export function allergyNoteReadCopy(who: string | null, time: string): string {
  return who ? `Read by ${who} at ${time}` : `Read at ${time}`
}

/** The server refused (403): this account cannot act on the order's shop. */
export const ALLERGY_NOTE_ACK_FORBIDDEN_COPY =
  "Not marked as read: your account cannot act on this shop's orders."

/** Anything else: the acknowledgement was not recorded, so the note is still unread. */
export const ALLERGY_NOTE_ACK_FAILED_COPY =
  "Not marked as read: the request did not go through. Try again."

// ---------------------------------------------------------------------------------------------
// 31.1-22 (D-07, #784): how the order was placed and what the customer confirmed, said to the
// VENDOR. The customer-facing form of the same four states lives in recorded-allergen-set.tsx
// (acknowledgementStatement); the vendor surface maps the SAME four kinds to these sentences, so
// the two can differ in voice but never in which state an order is in.
// ---------------------------------------------------------------------------------------------

function withWhen(sentence: string, when: string | null): string {
  return when ? `${sentence} — ${when}` : sentence
}

/** A storefront order: the names the customer ticked, and when the server accepted them (UK time). */
export function customerConfirmedAllergensCopy(names: readonly string[], when: string | null): string {
  return withWhen(`Customer confirmed allergens: ${names.join(", ")}`, when)
}

/** A storefront order whose basket declared none of the 14: a confirmation, not an absence. */
export function customerConfirmedNoneCopy(when: string | null): string {
  return withWhen("Customer confirmed the order declared none of the 14 regulated allergens", when)
}

/** D-07: the shop keyed the order in, so there was nothing for a customer to confirm. */
export const VENDOR_PLACED_ORDER_COPY = "Placed by the shop — no customer allergen confirmation recorded"

/** An order from before confirmations were recorded (D-06): no claim either way. */
export const CUSTOMER_CONFIRMATION_NOT_RECORDED_COPY = "Customer allergen confirmation: not recorded"

// ---------------------------------------------------------------------------------------------
// 31.1-23 (#860, D-18): which dish in the basket carries which declared allergen. The combined
// declared set is still stated on its own (the checkout panel's chips); these lines only say
// where each allergen comes from, so a customer deciding what to drop need not reopen every dish.
// ---------------------------------------------------------------------------------------------

/** The line above the per-allergen attribution in the checkout panel. */
export const ALLERGEN_ATTRIBUTION_INTRO_COPY = "Which dishes contain them:"

/** "Milk — Jollof Rice, Puff Puff": one declared allergen and the dishes, in basket order, that declare it. */
export function allergenAttributionCopy(allergen: string, dishes: readonly string[]): string {
  return `${allergen} — ${dishes.join(", ")}`
}
