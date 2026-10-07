"use client"

/**
 * One basket line's own allergens, on the basket page and in the cart drawer (31.1-23; #860, D-18).
 *
 * READ FROM THE LIVE CATALOGUE, NEVER FROM THE CART. The cart in localStorage holds productId,
 * title, quantity and price, and deliberately no allergen data: a mask copied into storage would
 * go on being shown after the vendor changed the declaration. Both surfaces fetch
 * `/public/shops/{slug}/products` and look each line up by productId, so a stale field that found
 * its way into a stored line is never read, and nothing is ever written back.
 *
 * Three states per line, never conflated:
 *   - loading           "Checking allergen information…" (holds the line's space, so the row does
 *                       not jump when the answer arrives);
 *   - NOT RECORDED      the catalogue did not load, or no longer lists the dish: "Allergen
 *                       information not available – check with the shop". NEVER "No allergens
 *                       declared", which would be a declaration nobody made;
 *   - stated            what `productAllergenStatement` (lib/product-allergens.ts) says for the
 *                       dish: the declared set ("Contains: Milk") or "No allergens declared", and
 *                       the D-09 line on its own when the ingredients name an undeclared allergen.
 *                       The same rule the menu card uses, so a dish reads the same in both places.
 */

import { useEffect, useState } from "react"

import publicApiClient from "@/lib/public-api-client"
import { indexProductsById, lineAllergens, type ProductIndex } from "@/lib/basket-allergens"
import {
  BASKET_LINE_ALLERGENS_LOADING_COPY,
  BASKET_LINE_ALLERGENS_UNAVAILABLE_COPY,
} from "@/lib/allergen-copy"

/**
 * The shop's live catalogue indexed by product id: `undefined` while loading, `null` when it could
 * not be loaded or was unusable (NOT RECORDED), else the index. Fetched once per mount, so a
 * component mounted each time a surface opens (the drawer's sheet content) re-reads it each time.
 */
export function useCatalogueIndex(slug: string): ProductIndex | undefined {
  const [loaded, setLoaded] = useState<{ slug: string; index: ProductIndex } | null>(null)

  useEffect(() => {
    let cancelled = false
    publicApiClient
      .get(`/public/shops/${slug}/products`)
      .then((res) => {
        if (!cancelled) setLoaded({ slug, index: indexProductsById(res.data) })
      })
      .catch(() => {
        // NOT RECORDED, never "nothing declared".
        if (!cancelled) setLoaded({ slug, index: null })
      })
    return () => {
      cancelled = true
    }
  }, [slug])

  return loaded && loaded.slug === slug ? loaded.index : undefined
}

export function BasketLineAllergens({
  productId,
  index,
}: {
  productId: string
  /** From `useCatalogueIndex`: undefined = loading, null = NOT RECORDED. */
  index: ProductIndex | undefined
}) {
  if (index === undefined) {
    return (
      <div data-testid="basket-line-allergens" className="mt-0.5 text-xs text-slate-600">
        <p>{BASKET_LINE_ALLERGENS_LOADING_COPY}</p>
      </div>
    )
  }

  const statement = lineAllergens({ productId }, index)
  if (!statement) {
    return (
      <div data-testid="basket-line-allergens" className="mt-0.5 text-xs text-amber-800">
        <p>{BASKET_LINE_ALLERGENS_UNAVAILABLE_COPY}</p>
      </div>
    )
  }

  const declaredLine = statement.containsLine ?? statement.noneDeclaredLine
  return (
    <div data-testid="basket-line-allergens" className="mt-0.5 text-xs text-amber-800">
      {declaredLine && <p>{declaredLine}</p>}
      {statement.undeclaredLine && <p>{statement.undeclaredLine}</p>}
    </div>
  )
}
