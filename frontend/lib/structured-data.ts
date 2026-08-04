import { SCHEMA_DAYS, parseHoursRange } from "@/lib/opening-hours"
import type { PublicProduct, PublicShop, ProductsByCategory } from "@/types/storefront"

/**
 * schema.org JSON-LD for the public storefront (issue #447, QA finding
 * F-H9-SEOMETA).
 *
 * WHY. The council measured **zero** JSON-LD across 28 priced, allergen-tagged
 * dishes. Rich results for food are exactly what a local-search user sees, and
 * every field below already exists in the database — the markup was the only
 * thing missing. For J'Toye this is vendor reach, which is the product's stated
 * value proposition, not polish.
 *
 * TWO RULES THIS FILE FOLLOWS.
 *
 * 1. **No hostname is ever written here.** Every absolute URL is built from an
 *    `origin` the caller resolved from injected configuration
 *    (`lib/public-origin.ts`). When no origin can be trusted the URLs come out
 *    RELATIVE, which consumers resolve against the document — a correct answer
 *    on any host, and specifically not a guess at the unsettled production
 *    domain.
 *
 * 2. **Nothing is asserted that the data does not support.** An absent field is
 *    omitted rather than defaulted: a `priceRange` invented from no products, or
 *    an `aggregateRating` whose count does not match the ratings it averaged, is
 *    a structured-data penalty rather than a win.
 */

/** Resolve `path` against `origin`, or leave it relative when there is none. */
export function abs(origin: string | null, path: string): string {
  if (!origin) return path
  try {
    return new URL(path, origin).toString()
  } catch {
    return path
  }
}

/**
 * Serialise for embedding in `<script type="application/ld+json">`.
 *
 * `<` is escaped because the payload carries vendor-authored strings (shop
 * description, dish titles): a literal `</script>` inside any of them would end
 * the element early and turn the rest of the document into markup. `<` is
 * still a valid JSON escape, so parsers read it identically.
 */
export function serialiseJsonLd(data: unknown): string {
  return JSON.stringify(data).replace(/</g, "\\u003c")
}

function pounds(pennies: number): string {
  return (pennies / 100).toFixed(2)
}

/** `"Grill, Peri Peri, Halal"` -> `["Grill","Peri Peri","Halal"]` */
function splitTags(tags: string | null | undefined): string[] {
  return (
    tags
      ?.split(",")
      .map((t) => t.trim())
      .filter(Boolean) ?? []
  )
}

function openingHoursSpecification(hours: Record<string, string> | null | undefined) {
  if (!hours) return undefined
  const spec = Object.entries(hours).flatMap(([day, value]) => {
    const dayOfWeek = SCHEMA_DAYS[day.toLowerCase().slice(0, 3)]
    const range = parseHoursRange(value)
    if (!dayOfWeek || !range) return []
    return [
      {
        "@type": "OpeningHoursSpecification",
        dayOfWeek,
        opens: range.opens,
        closes: range.closes,
      },
    ]
  })
  return spec.length > 0 ? spec : undefined
}

/** Drop undefined values so the emitted JSON carries no empty keys. */
function compact<T extends Record<string, unknown>>(obj: T): T {
  return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== undefined)) as T
}

export interface ProductNodeOptions {
  origin: string | null
  /** The storefront page the dish lives on — Offer.url and Product.url. */
  shopUrl: string
  shopName: string
}

/** A single dish as `Product` + nested `Offer`. */
export function productNode(
  product: PublicProduct,
  { origin, shopUrl, shopName }: ProductNodeOptions
) {
  const image = product.imageUrls?.[0] || product.imageUrl || null
  return compact({
    "@type": "Product",
    name: product.title,
    description: product.description || undefined,
    image: image ? abs(origin, image) : undefined,
    category: product.category || undefined,
    brand: { "@type": "Brand", name: shopName },
    offers: compact({
      "@type": "Offer",
      price: pounds(product.pricePennies),
      priceCurrency: "GBP",
      // `inStock` is a real signal from the vendor's own catalogue, so it is
      // reported rather than assumed available.
      availability:
        product.inStock === false
          ? "https://schema.org/OutOfStock"
          : "https://schema.org/InStock",
      url: shopUrl,
    }),
  })
}

export interface ShopStructuredDataOptions {
  shop: PublicShop
  products: ProductsByCategory
  /** Average of the review SAMPLE that was fetched, not of all reviews. */
  avgRating: number
  /** How many reviews that average was computed from. See the note below. */
  ratedCount: number
  origin: string | null
}

/**
 * The `/shop/[slug]` graph: `Restaurant` + a `Menu` of its dishes + an
 * `ItemList` of `Product`/`Offer` nodes + a `BreadcrumbList`.
 *
 * `Restaurant` rather than a bare `LocalBusiness`: it is a subtype of
 * `FoodEstablishment` which is a subtype of `LocalBusiness`, so it satisfies the
 * LocalBusiness requirement in #447 while carrying the food-specific properties
 * (`servesCuisine`, `hasMenu`) that produce the richer result.
 */
export function shopStructuredData({
  shop,
  products,
  avgRating,
  ratedCount,
  origin,
}: ShopStructuredDataOptions): unknown[] {
  const shopPath = `/shop/${shop.slug}`
  const shopUrl = abs(origin, shopPath)
  const dishes = Object.values(products).flat()
  const prices = dishes.map((p) => p.pricePennies).filter((n) => Number.isFinite(n))
  const cuisines = splitTags(shop.tags)

  const restaurant = compact({
    "@context": "https://schema.org",
    "@type": "Restaurant",
    "@id": `${shopUrl}#restaurant`,
    name: shop.name,
    description: shop.description || undefined,
    url: shopUrl,
    image: shop.bannerUrl
      ? abs(origin, shop.bannerUrl)
      : shop.logoUrl
        ? abs(origin, shop.logoUrl)
        : undefined,
    logo: shop.logoUrl ? abs(origin, shop.logoUrl) : undefined,
    telephone: shop.phone || undefined,
    email: shop.email || undefined,
    address: shop.address
      ? { "@type": "PostalAddress", streetAddress: shop.address, addressCountry: "GB" }
      : undefined,
    geo:
      shop.latitude != null && shop.longitude != null
        ? {
            "@type": "GeoCoordinates",
            latitude: shop.latitude,
            longitude: shop.longitude,
          }
        : undefined,
    servesCuisine: cuisines.length > 0 ? cuisines : undefined,
    currenciesAccepted: "GBP",
    // Only meaningful when there is a menu to price.
    priceRange:
      prices.length > 0
        ? `£${pounds(Math.min(...prices))}-£${pounds(Math.max(...prices))}`
        : undefined,
    openingHoursSpecification: openingHoursSpecification(shop.openingHours),
    // DELIBERATE: `reviewCount` is the number of reviews the average was
    // actually computed from, NOT the shop's total. The page fetches a 5-review
    // sample and averages that; publishing the total against a 5-review mean
    // would be a rating the site cannot substantiate.
    aggregateRating:
      ratedCount > 0 && avgRating > 0
        ? {
            "@type": "AggregateRating",
            ratingValue: avgRating,
            reviewCount: ratedCount,
            bestRating: 5,
            worstRating: 1,
          }
        : undefined,
    hasMenu:
      dishes.length > 0
        ? {
            "@type": "Menu",
            name: `${shop.name} menu`,
            hasMenuSection: Object.entries(products)
              .filter(([, items]) => items.length > 0)
              .map(([category, items]) => ({
                "@type": "MenuSection",
                name: category,
                hasMenuItem: items.map((p) =>
                  compact({
                    "@type": "MenuItem",
                    name: p.title,
                    description: p.description || undefined,
                    offers: {
                      "@type": "Offer",
                      price: pounds(p.pricePennies),
                      priceCurrency: "GBP",
                    },
                  })
                ),
              })),
          }
        : undefined,
  })

  const nodes: unknown[] = [restaurant]

  if (dishes.length > 0) {
    nodes.push({
      "@context": "https://schema.org",
      "@type": "ItemList",
      name: `${shop.name} menu`,
      numberOfItems: dishes.length,
      itemListElement: dishes.map((product, i) => ({
        "@type": "ListItem",
        position: i + 1,
        item: productNode(product, { origin, shopUrl, shopName: shop.name }),
      })),
    })
  }

  nodes.push(breadcrumbs(origin, [
    { name: "Home", path: "/" },
    { name: "Kitchens", path: "/shop" },
    { name: shop.name, path: shopPath },
  ]))

  return nodes
}

/** The `/shop` graph: an `ItemList` of `Restaurant` nodes + a `BreadcrumbList`. */
export function shopListStructuredData(
  shops: PublicShop[],
  origin: string | null
): unknown[] {
  const nodes: unknown[] = []
  if (shops.length > 0) {
    nodes.push({
      "@context": "https://schema.org",
      "@type": "ItemList",
      name: "Local kitchens on J'Toye",
      numberOfItems: shops.length,
      itemListElement: shops.map((shop, i) => ({
        "@type": "ListItem",
        position: i + 1,
        item: compact({
          "@type": "Restaurant",
          name: shop.name,
          description: shop.description || undefined,
          url: abs(origin, `/shop/${shop.slug}`),
          image: shop.bannerUrl
            ? abs(origin, shop.bannerUrl)
            : shop.logoUrl
              ? abs(origin, shop.logoUrl)
              : undefined,
          address: shop.address
            ? {
                "@type": "PostalAddress",
                streetAddress: shop.address,
                addressCountry: "GB",
              }
            : undefined,
          servesCuisine: splitTags(shop.tags).length > 0 ? splitTags(shop.tags) : undefined,
        }),
      })),
    })
  }
  nodes.push(breadcrumbs(origin, [
    { name: "Home", path: "/" },
    { name: "Kitchens", path: "/shop" },
  ]))
  return nodes
}

function breadcrumbs(origin: string | null, trail: { name: string; path: string }[]) {
  return {
    "@context": "https://schema.org",
    "@type": "BreadcrumbList",
    itemListElement: trail.map((crumb, i) => ({
      "@type": "ListItem",
      position: i + 1,
      name: crumb.name,
      item: abs(origin, crumb.path),
    })),
  }
}
