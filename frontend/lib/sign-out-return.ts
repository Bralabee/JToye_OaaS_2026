import { safeReturnTo } from "@/lib/safe-return-to"

/**
 * SW-JRD-08 — where a storefront sign-out returns the customer.
 *
 * It used to be /shop from everywhere, so signing out on a shop's page lost the page. Now a sign-out
 * returns to the page it was made on WHEN that page is public: the directory (/shop) or a shop's own
 * page (/shop/<slug>). This is an ALLOW-list: every other path falls back to /shop, because each is
 * personal or mid-journey and the sign-out has just removed what it depended on — the account and
 * my-orders pages, sign-in and the auth callback, a basket and a checkout (the baskets are cleared
 * on sign-out, #459), and an order page.
 *
 * Only the destination is decided here. The teardown (marker, baskets, identity stamp) is
 * `customerLogout`'s and does not depend on it.
 */
const FALLBACK = "/shop"

/** First path segments under /shop that are app routes, not shop slugs. */
const RESERVED_SHOP_SEGMENTS = new Set(["account", "orders", "signin", "auth"])

const SHOP_SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/i

export function signOutReturnPath(pathname: string | null | undefined): string {
  const path = safeReturnTo(pathname, FALLBACK)
  const segments = path.split("?")[0].split("#")[0].split("/").filter(Boolean)
  if (segments[0] !== "shop") return FALLBACK
  if (segments.length === 1) return FALLBACK
  if (segments.length === 2 && SHOP_SLUG.test(segments[1]) && !RESERVED_SHOP_SEGMENTS.has(segments[1])) {
    return `/shop/${segments[1]}`
  }
  return FALLBACK
}
