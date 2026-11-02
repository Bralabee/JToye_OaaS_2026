import type { PublicShop } from "@/types/storefront"

/**
 * Featured kitchens on the landing row (quick 261102-r6u, owner decisions
 * D-02/D-03, design D17). Pure: no fetch, no React, no state.
 *
 * `featured` arrives from `GET /public/featured-shops` already in the order the
 * environment configured, and already restricted to published shops by the
 * server. `listing` is page 0 of the public directory in its own (name) order.
 */

/**
 * The featured shops first, in configured order, then every listing entry whose
 * slug is not featured, in the listing's own order. No slug appears twice: a
 * featured shop that is also on the listing page keeps its FEATURED position.
 * With nothing featured, the listing is returned unchanged.
 */
export function orderWithFeatured(
  featured: readonly PublicShop[],
  listing: readonly PublicShop[]
): PublicShop[] {
  if (featured.length === 0) return [...listing]
  const seen = new Set<string>()
  const merged: PublicShop[] = []
  for (const shop of [...featured, ...listing]) {
    if (seen.has(shop.slug)) continue
    seen.add(shop.slug)
    merged.push(shop)
  }
  return merged
}

/** The slugs of the featured shops, in configured order. */
export function featuredSlugsOf(featured: readonly PublicShop[]): string[] {
  return featured.map((shop) => shop.slug)
}
