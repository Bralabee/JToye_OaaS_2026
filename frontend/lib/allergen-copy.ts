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
// the vendor order detail, and the shop's acknowledgement of it.
// RED skeleton: every name is exported with a wrong value so the tests fail on assertions.
// ---------------------------------------------------------------------------------------------

export const ALLERGY_NOTE_LABEL_COPY = ""
export const ALLERGY_NOTE_ACK_BUTTON_COPY = ""
export const ALLERGY_NOTE_NOT_READ_PRINT_COPY = ""
export const ALLERGY_NOTE_READER_YOU_COPY = ""
export const ALLERGY_NOTE_READER_STAFF_COPY = ""
export function allergyNoteReadCopy(_who: string | null, _time: string): string {
  return ""
}
export const ALLERGY_NOTE_ACK_FORBIDDEN_COPY = ""
export const ALLERGY_NOTE_ACK_FAILED_COPY = ""
