import { safeReturnTo } from "@/lib/safe-return-to"
import { PUBLIC_STATIC_ROUTES } from "@/lib/public-routes"
import { SHOP_SLUG_PATTERN } from "@/lib/shop-slug"

/**
 * SW-JRD-08 — where a customer sign-out returns the customer.
 *
 * It used to be /shop from everywhere, so signing out lost the page. Now a sign-out returns to the
 * page it was made on WHEN that page is public, and to /shop otherwise:
 *  - a page `sitemap.xml` lists (`PUBLIC_STATIC_ROUTES`, the same list) returns to itself;
 *  - anything under a shop's own path returns to that shop's page, /shop/<slug>: its basket and its
 *    checkout included, so the customer stays at that kitchen (#1109 review round 1);
 *  - a shop's order page (/shop/<slug>/orders/...) and every other path — account, my orders,
 *    sign-in, the auth callback, /track, the dashboard — falls back to /shop.
 *
 * Only the destination is decided here. The teardown (marker, baskets, identity stamp) is
 * `customerLogout`'s and does not depend on it.
 */
const FALLBACK = "/shop"

/**
 * First path segments under /shop that are app routes, not shop slugs: the static route directories
 * of `app/shop` (a Jest test derives them from the file system and fails on drift), which the server
 * also refuses as slugs (`jtoye.shop.reserved-slugs`).
 */
export const RESERVED_SHOP_SEGMENTS: ReadonlySet<string> = new Set(["account", "orders", "signin", "auth"])

/** Sub-routes of a shop that are a customer's own: signing out there does not return to them. */
const PRIVATE_SHOP_SUBROUTES: ReadonlySet<string> = new Set(["orders"])

const PUBLIC_STATIC_PATHS: ReadonlySet<string> = new Set(PUBLIC_STATIC_ROUTES.map((r) => r.path))

export function signOutReturnPath(pathname: string | null | undefined): string {
  const raw = safeReturnTo(pathname, FALLBACK).split("?")[0].split("#")[0]
  const path = raw.length > 1 ? raw.replace(/\/+$/, "") : raw
  if (PUBLIC_STATIC_PATHS.has(path)) return path

  const segments = path.split("/").filter(Boolean)
  if (segments[0] !== "shop" || segments.length < 2) return FALLBACK
  const slug = segments[1]
  if (!SHOP_SLUG_PATTERN.test(slug) || RESERVED_SHOP_SEGMENTS.has(slug)) return FALLBACK
  if (segments.length > 2 && PRIVATE_SHOP_SUBROUTES.has(segments[2])) return FALLBACK
  return `/shop/${slug}`
}
