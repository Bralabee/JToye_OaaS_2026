/**
 * Which dish in the basket carries which allergen (31.1-23; #860, D-18).
 *
 * Pure functions over the basket lines and the LIVE storefront catalogue. Nothing here is ever
 * written to the basket's storage: the cart holds productId/title/quantity/price and no allergen
 * data, because a mask copied into localStorage would go on being shown after the vendor changed
 * it. Every surface reads the catalogue it has just fetched.
 *
 * THE RULES (each one is a way of telling an allergic customer something false):
 *   - NOT RECORDED propagates. A line whose product the catalogue cannot resolve makes the
 *     basket-level answers `null`, never a partial list. A partial attribution under-states the
 *     basket, and under-stating is the direction that injures. Same rule as the checkout's
 *     `basketAllergenMask`, and both read the same index.
 *   - DECLARED ONLY in the attribution. The vendor's mask is the legally operative statement; the
 *     D-09 undeclared-ingredient names are advisory and surface as their own flags, and may-contain
 *     is a separate statement again. None is ever OR-ed into another.
 *   - ORDER. Allergens in catalogue bit order (the order of the panel's chips and of the shared
 *     ALLERGENS table); dishes in basket order, named as the basket lists them.
 *
 * What a single line SAYS is decided by `productAllergenStatement` (lib/product-allergens.ts), the
 * same rule the menu card and dish modal use, so a dish cannot read one way on the menu and another
 * in the basket.
 */

import { ALLERGENS, type OrderAllergenFlag } from "@/types/api"
import type { PublicProduct } from "@/types/storefront"
import { productAllergenStatement, type ProductAllergenStatement } from "@/lib/product-allergens"

/** The storefront catalogue keyed by product id; `null` = not loaded / unusable (NOT RECORDED). */
export type ProductIndex = Map<string, PublicProduct> | null

/** The two fields of a basket line these functions read. */
export interface BasketLine {
  productId: string
  title: string
}

/** One declared allergen and the dishes, in basket order, whose declaration includes it. */
export interface AllergenAttribution {
  allergen: string
  dishes: string[]
}

/**
 * Index the storefront's `/public/shops/{slug}/products` payload (category -> products) by id,
 * defensively. Moved here from the checkout page (31.1-15) so the basket page and the cart drawer
 * read the catalogue through the same gate.
 *
 * Returns `null` — meaning NOT RECORDED, never "nothing declared" — when the payload is missing,
 * malformed, or yields no usable product. That distinction is the whole point: an allergen panel
 * that says "the kitchen declared none of the 14" because a fetch failed is stating something the
 * kitchen never said, and that is the direction that injures someone.
 */
export function indexProductsById(data: unknown): Map<string, PublicProduct> | null {
  if (!data || typeof data !== "object") return null
  const index = new Map<string, PublicProduct>()
  for (const group of Object.values(data as Record<string, unknown>)) {
    if (!Array.isArray(group)) continue
    for (const candidate of group) {
      if (
        candidate &&
        typeof candidate === "object" &&
        typeof (candidate as PublicProduct).id === "string" &&
        typeof (candidate as PublicProduct).allergenMask === "number"
      ) {
        index.set((candidate as PublicProduct).id, candidate as PublicProduct)
      }
    }
  }
  return index.size > 0 ? index : null
}

function resolve(productId: string, index: ProductIndex): PublicProduct | null {
  const product = index?.get(productId)
  return product && typeof product.allergenMask === "number" ? product : null
}

/** One line's own allergen statement from the live catalogue; `null` = NOT RECORDED for this line. */
export function lineAllergens(item: { productId: string }, index: ProductIndex): ProductAllergenStatement | null {
  const product = resolve(item.productId, index)
  return product ? productAllergenStatement(product) : null
}

/** Every line's statement, in basket order, or `null` when the basket is empty or any line is unresolved. */
function resolvedStatements(
  items: readonly BasketLine[],
  index: ProductIndex
): { line: BasketLine; statement: ProductAllergenStatement }[] | null {
  if (!index || items.length === 0) return null
  const out: { line: BasketLine; statement: ProductAllergenStatement }[] = []
  for (const line of items) {
    const statement = lineAllergens(line, index)
    if (!statement) return null
    out.push({ line, statement })
  }
  return out
}

/**
 * Each DECLARED allergen in the basket and the dishes carrying it: allergens in catalogue bit
 * order, dishes in basket order, a shared allergen listed once. `[]` = every line resolved and none
 * declares any of the 14; `null` = NOT RECORDED.
 */
export function basketAllergenAttribution(
  items: readonly BasketLine[],
  index: ProductIndex
): AllergenAttribution[] | null {
  const lines = resolvedStatements(items, index)
  if (!lines) return null
  const attribution: AllergenAttribution[] = []
  for (const { name } of ALLERGENS) {
    const dishes = lines.filter(({ statement }) => statement.declared.includes(name)).map(({ line }) => line.title)
    if (dishes.length > 0) attribution.push({ allergen: name, dishes })
  }
  return attribution
}

const BIT_BY_NAME = new Map(ALLERGENS.map((a) => [a.name, a.bit]))

/**
 * Each line's D-09 undeclared-ingredient names (the server's reconciliation, 31.1-06) as advisory
 * flags naming the dish, in basket order then catalogue order. A name the dish already declares
 * adds nothing (productAllergenStatement drops it). A name the shared table does not know keeps
 * bit -1 rather than being dropped: losing an allergen statement to a spelling drift is the silent
 * failure the parity test exists to prevent. `null` = NOT RECORDED.
 */
export function basketAllergenFlags(items: readonly BasketLine[], index: ProductIndex): OrderAllergenFlag[] | null {
  const lines = resolvedStatements(items, index)
  if (!lines) return null
  return lines.flatMap(({ line, statement }) =>
    statement.undeclared.map((allergenName) => ({
      productName: line.title,
      allergenName,
      allergenBit: BIT_BY_NAME.get(allergenName) ?? -1,
    }))
  )
}

/**
 * True only when the attribution names exactly the declared set the panel is stating, in order.
 * After a stale 409 the panel shows the SERVER's newer set; an attribution computed from a client
 * catalogue that disagrees with it would attribute a set the panel does not state, so it is
 * withdrawn rather than shown beside a contradiction.
 */
export function attributionAgreesWith(
  attribution: readonly AllergenAttribution[] | null,
  declaredNames: readonly string[] | null
): boolean {
  if (!attribution || !declaredNames) return false
  return (
    attribution.length === declaredNames.length &&
    attribution.every((entry, i) => entry.allergen === declaredNames[i])
  )
}
