import { cache } from "react"
import { coreBaseUrl } from "@/lib/customer-orders-server"
import { isOpenNow } from "@/lib/opening-hours"
import type { PageResponse } from "@/types/api"
import type {
  PublicAnnouncement,
  PublicProduct,
  PublicPromotion,
  PublicShop,
  ProductsByCategory,
  Review,
  ShopDetail,
} from "@/types/storefront"

export type { ShopDetail } from "@/types/storefront"

/**
 * SERVER ONLY. Reads the PUBLIC storefront straight from the core API so
 * `/shop` and `/shop/[slug]` can be delivered as HTML instead of after
 * hydration (issues #507, #447).
 *
 * "Server only" is a convention here rather than a compile-time guarantee — the
 * `server-only` package is not a dependency of this project — and it matters for
 * the same reason it matters in `customer-orders-server.ts`: this module
 * resolves the INTERNAL core host, which is meaningless in a browser and is a
 * small piece of infrastructure disclosure. Nothing here is imported from a
 * `"use client"` file; the client islands keep using `publicApiClient` against
 * the browser-facing origin.
 *
 * WHY THE PAGES NEEDED THIS AT ALL. `"use client"` is not what broke SSR — a
 * client component IS rendered to HTML on the server. What broke it is fetching
 * in `useEffect`, which only ever runs in a browser, so the server could only
 * ever render the loading branch. Measured on the running stack before this
 * change, `/shop/brixton-village-grill` served 34,419 bytes containing one
 * spinner, zero `<h1>` and zero occurrences of the string "Brixton Village
 * Grill". A crawler and a first paint both got the spinner.
 *
 * THE RETURN SHAPE IS THREE-VALUED ON PURPOSE. The pages have a carefully built
 * distinction between "this shop does not exist" and "we could not get an
 * answer" (F-RATE / #88: a public 429 must surface a transient busy state and
 * must never fall through to the authoritative "Shop not found"). Collapsing
 * that into `data | null` on the server would have thrown the distinction away,
 * so it is preserved:
 *
 *   ok       — render it, and skip the client fetch entirely
 *   notfound — a hard 404 from core; the route answers 404 rather than a soft one
 *   defer    — 429, 5xx, DNS, timeout: the server has no authoritative answer,
 *              so hand to the client island, which already owns the retry
 *              budget, the backoff and the empty-state policy. Deferring
 *              reproduces today's behaviour exactly rather than inventing a new
 *              one on a path that is hard to exercise.
 */

export type StorefrontLoad<T> =
  | { state: "ok"; data: T }
  | { state: "notfound" }
  | { state: "defer" }

/** The storefront index page's data. */
export type ShopList = PageResponse<PublicShop>

const NO_STORE: RequestInit = { cache: "no-store" }

/**
 * One fetch, decoded, never throwing.
 *
 * `notfound` is returned ONLY for a real 404. Everything else that is not a 2xx
 * — including 429 — becomes `defer`, because the caller must not present a
 * non-answer as an authoritative one.
 */
async function getJson<T>(path: string): Promise<StorefrontLoad<T>> {
  try {
    const res = await fetch(`${coreBaseUrl()}${path}`, NO_STORE)
    if (res.status === 404) return { state: "notfound" }
    if (!res.ok) return { state: "defer" }
    const body = (await res.json()) as T
    if (body == null) return { state: "defer" }
    return { state: "ok", data: body }
  } catch {
    // DNS / connect / timeout / malformed JSON. Not an authoritative answer.
    return { state: "defer" }
  }
}

/**
 * An OPTIONAL sub-resource: reviews, config, promotions, announcements.
 *
 * These already `.catch()` to defaults on the client, so a failure in one of
 * them must not take down the page or downgrade it to `defer` — the menu is
 * what the customer came for.
 */
async function getOptional<T>(path: string, fallback: T): Promise<T> {
  const r = await getJson<T>(path)
  return r.state === "ok" ? r.data : fallback
}

export interface ShopListParams {
  page?: number
  size?: number
  q?: string
}

/** The public shop directory, page 0 by default — mirrors the client's call. */
export async function loadShopList({
  page = 0,
  size = 12,
  q,
}: ShopListParams = {}): Promise<StorefrontLoad<ShopList>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const term = q?.trim()
  if (term) params.set("q", term)
  const r = await getJson<ShopList>(`/public/shops?${params.toString()}`)
  // The directory has no "this list does not exist" state; a 404 here is as
  // unauthoritative as a 500.
  return r.state === "notfound" ? { state: "defer" } : r
}

/**
 * One shop and everything its page shows.
 *
 * The two critical calls (shop + products) decide the outcome; the four
 * optional ones degrade to empty. That split is the same one the client made,
 * kept so the page cannot start failing for a reason it used to tolerate.
 *
 * WRAPPED IN `react.cache` BECAUSE IT IS CALLED TWICE PER REQUEST — once by
 * `generateMetadata` (which needs the shop's name and description for the title,
 * canonical and OG tags) and once by the page (which needs the menu). Without
 * memoisation that is 10 upstream calls per page view instead of 5, on public
 * endpoints that are rate-limited per client. `cache` is per-request, so it
 * cannot serve one visitor's shop to another.
 */
export const loadShopDetail = cache(async function loadShopDetail(
  slug: string
): Promise<StorefrontLoad<ShopDetail>> {
  const encoded = encodeURIComponent(slug)

  const [shopRes, productsRes, reviews, promotions, announcements] = await Promise.all([
    getJson<PublicShop>(`/public/shops/${encoded}`),
    getJson<ProductsByCategory>(`/public/shops/${encoded}/products`),
    getOptional<{ content: Review[]; totalElements: number }>(
      `/public/shops/${encoded}/reviews?size=5`,
      { content: [], totalElements: 0 }
    ),
    getOptional<PublicPromotion[]>(`/public/shops/${encoded}/promotions`, []),
    getOptional<PublicAnnouncement[]>(`/public/shops/${encoded}/announcements`, []),
  ])

  if (shopRes.state === "notfound") return { state: "notfound" }
  if (shopRes.state !== "ok") return { state: "defer" }
  // A shop that exists with an unreadable menu is still a defer, not an empty
  // menu: "No items yet" is a claim about the vendor, not about the network.
  if (productsRes.state !== "ok") return { state: "defer" }

  const sample = reviews.content ?? []
  const avgRating =
    sample.length > 0
      ? Math.round(
          (sample.reduce((sum, r) => sum + r.foodRating, 0) / sample.length) * 10
        ) / 10
      : 0

  return {
    state: "ok",
    data: {
      shop: shopRes.data,
      products: productsRes.data,
      reviews: sample,
      reviewCount: reviews.totalElements ?? 0,
      avgRating,
      promotions: promotions ?? [],
      announcements: announcements ?? [],
      isOpen: isOpenNow(shopRes.data.openingHours),
    },
  }
})

/**
 * Slugs for the sitemap. Deliberately separate from `loadShopList` so the
 * sitemap can ask for a larger page without changing what the index page
 * fetches, and so a sitemap failure is a distinguishable empty array rather
 * than a thrown error that would 500 `/sitemap.xml`.
 */
export async function loadAllShopSlugs(limit = 500): Promise<string[]> {
  const r = await loadShopList({ page: 0, size: limit })
  if (r.state !== "ok") return []
  return (r.data.content ?? []).map((s) => s.slug).filter(Boolean)
}

/** Flatten the category map — used for structured data and price ranges. */
export function allProducts(products: ProductsByCategory): PublicProduct[] {
  return Object.values(products).flat()
}
