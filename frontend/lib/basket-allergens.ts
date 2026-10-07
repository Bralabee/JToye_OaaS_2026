/**
 * RED skeleton (31.1-23 Task 1). Replaced in GREEN.
 */

import type { OrderAllergenFlag } from "@/types/api"
import type { PublicProduct } from "@/types/storefront"
import type { ProductAllergenStatement } from "@/lib/product-allergens"

export type ProductIndex = Map<string, PublicProduct> | null

export interface BasketLine {
  productId: string
  title: string
}

export interface AllergenAttribution {
  allergen: string
  dishes: string[]
}

export function lineAllergens(item: { productId: string }, index: ProductIndex): ProductAllergenStatement | null {
  void item
  void index
  return null
}

export function basketAllergenAttribution(items: readonly BasketLine[], index: ProductIndex): AllergenAttribution[] | null {
  void items
  void index
  return null
}

export function basketAllergenFlags(items: readonly BasketLine[], index: ProductIndex): OrderAllergenFlag[] | null {
  void items
  void index
  return null
}

export function attributionAgreesWith(
  attribution: readonly AllergenAttribution[] | null,
  declaredNames: readonly string[] | null
): boolean {
  void attribution
  void declaredNames
  return false
}
