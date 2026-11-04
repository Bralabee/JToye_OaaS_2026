import { ALLERGENS } from "@/types/api"

/**
 * #862 (owner ruling 2026-11-04, persona close-out): the storefront allergen filter reflects what
 * the VENDOR DECLARES ("declares peanuts"), never "free from". Wording lives here, in one place,
 * so the menu and the kitchen directory cannot drift apart and a test can hold the forbidden
 * phrases out of both.
 *
 * Why the boundary: a dish missing from a "declares X" list is NOT thereby free of X (the vendor
 * may have declared nothing, or declared wrongly, and "may contain" is a separate statement).
 * Phrasing the filter as "free from" would invite an allergic customer to treat "not declared" as
 * "safe".
 */
export const DECLARED_FILTER_LABEL = "Show dishes that declare"
export const DECLARED_FILTER_ANY = "Any allergen"

/** Shown with the control, always. States what the list is and what it is not. */
export const DECLARED_FILTER_HELP =
  "Lists dishes whose kitchen has declared this allergen. It reflects only what each kitchen has declared: a dish that is not listed is not confirmed free of it. Ask the kitchen about your allergy before you order."

export function declaredFilterActiveNote(allergenName: string, scope: "kitchens" | "dishes"): string {
  return scope === "kitchens"
    ? `Showing kitchens with a dish that declares ${allergenName}.`
    : `Showing dishes that declare ${allergenName}.`
}

/** The allergen a free-text query names (catalogue name, plural or not), or a generic label. */
export function allergenNameIn(text: string): string | null {
  const lower = text.toLowerCase()
  for (const a of ALLERGENS) {
    const stem = a.name.toLowerCase().replace(/s$/, "")
    if (new RegExp(`\\b${stem}s?\\b`).test(lower)) return a.name
  }
  if (/\b(dairy|soya|soy|egg|nut)s?\b/.test(lower)) return "that allergen"
  return null
}

/**
 * True when `query` asks for the ABSENCE of an allergen ("gluten free", "peanut-free", "without
 * nuts", "no dairy"). The platform cannot answer that, and must say so rather than let the kitchens
 * that DECLARE the allergen read as the answer.
 */
export function isFreeFromQuery(query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return false
  const asksAbsence =
    /\b(?:free[ -]?from|without|no|non[ -]?)\b/.test(q) || /\b[a-z]+[ -]?free\b/.test(q)
  return asksAbsence && allergenNameIn(q) !== null
}

export const FREE_FROM_QUERY_NOTE =
  "We can show dishes that declare an allergen, not dishes that are free from one. Use the allergen filter to see what kitchens declare, and ask the kitchen about your allergy."

/** The filter options: the 14 UK FSA allergens, in catalogue order. */
export const DECLARED_FILTER_OPTIONS = ALLERGENS.map((a) => ({ bit: a.bit, name: a.name }))
