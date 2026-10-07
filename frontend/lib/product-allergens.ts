/**
 * What a storefront surface says about one product's allergens (31.1-21; #817, #787, #861;
 * D-09, D-16, D-18). The menu card and the dish modal both read it, so the two cannot disagree
 * about which state a dish is in.
 *
 * Three statements, kept apart and never merged into one another:
 *   - DECLARED: the vendor's `allergenMask`, the legally operative statement. Names come from the
 *     shared 14-entry table (types/api.ts ALLERGENS, parity-tested against core-java's
 *     AllergenCatalog), so every surface spells and orders them identically.
 *   - UNDECLARED INGREDIENT (D-09): names the server's reconciliation (31.1-06) found emphasised in
 *     the ingredients but missing from the declaration. Advisory. The browser never parses the
 *     ingredients itself; it only renders what the server computed.
 *   - MAY CONTAIN (D-16): cross-contact names the vendor recorded that are not already declared.
 *
 * "No allergens declared" is said only when the declaration is empty AND nothing is flagged: a
 * product whose ingredients name MILK must never read as having nothing to declare.
 *
 * Sentences come from lib/allergen-copy.ts; this module decides only WHICH of them apply.
 */

import { ALLERGENS, getAllergenNames } from "@/types/api"
import {
  NO_ALLERGENS_DECLARED_COPY,
  containsCopy,
  mayContainCopy,
  undeclaredIngredientCopy,
} from "@/lib/allergen-copy"
import type { PublicProduct } from "@/types/storefront"

export interface ProductAllergenStatement {
  /** Declared names, catalogue bit order. */
  declared: string[]
  /** "Contains: Gluten, Milk", or null for an empty declaration. */
  containsLine: string | null
  /** "No allergens declared" when nothing is declared AND nothing is flagged; otherwise null. */
  noneDeclaredLine: string | null
  /** Undeclared ingredient names (D-09), catalogue order, never a declared one. */
  undeclared: string[]
  /** "Ingredients name: MILK – check with the shop", or null. */
  undeclaredLine: string | null
  /** May-contain names (D-16), catalogue order, never a declared one. */
  mayContain: string[]
  /** "May contain: Sesame", or null. */
  mayContainLine: string | null
}

const CATALOGUE_POSITION = new Map(ALLERGENS.map((a, index) => [a.name, index]))

/**
 * Server-sent names in catalogue bit order, minus any already declared, without duplicates.
 * A name the table does not know is KEPT (after the known ones): dropping an allergen statement
 * because of a spelling drift would be the silent failure this module exists to prevent; the
 * parity test is what keeps the spellings aligned.
 */
function advisoryNames(names: readonly string[] | null | undefined, declared: readonly string[]): string[] {
  if (!names || names.length === 0) return []
  const unique = Array.from(new Set(names)).filter((name) => !declared.includes(name))
  return unique.sort(
    (a, b) =>
      (CATALOGUE_POSITION.get(a) ?? Number.MAX_SAFE_INTEGER) -
      (CATALOGUE_POSITION.get(b) ?? Number.MAX_SAFE_INTEGER)
  )
}

export function productAllergenStatement(
  product: Pick<PublicProduct, "allergenMask" | "undeclaredIngredientAllergens" | "mayContainAllergens">
): ProductAllergenStatement {
  const declared = getAllergenNames(product.allergenMask ?? 0)
  const undeclared = advisoryNames(product.undeclaredIngredientAllergens, declared)
  const mayContain = advisoryNames(product.mayContainAllergens, declared)
  return {
    declared,
    containsLine: containsCopy(declared),
    noneDeclaredLine: declared.length === 0 && undeclared.length === 0 ? NO_ALLERGENS_DECLARED_COPY : null,
    undeclared,
    undeclaredLine: undeclaredIngredientCopy(undeclared),
    mayContain,
    mayContainLine: mayContainCopy(mayContain),
  }
}
