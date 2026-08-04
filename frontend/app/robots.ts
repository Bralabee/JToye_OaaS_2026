import type { MetadataRoute } from "next"
import { resolvePublicOrigin } from "@/lib/public-origin"

/**
 * `/robots.txt` — issue #447, QA finding F-H8-ROBOTS.
 *
 * There was no `app/robots.ts` and no `public/robots.txt`, so `/robots.txt`
 * returned **404** while `/sitemap.xml` returned 200. Nothing anywhere pointed a
 * crawler at the sitemap, so the sitemap was reachable only by guessing its
 * conventional path. (The 200 on sitemap.xml was the council's control arm,
 * which is what made the 404 a specific finding rather than a probe failure.)
 *
 * `force-dynamic` because the sitemap URL below is resolved from a RUNTIME
 * environment variable. Without it Next prerenders this file at build time and
 * bakes whatever origin the builder happened to have — which is exactly the trap
 * `app/sitemap.ts` was in (it built to a `○ (Static)` route carrying a
 * `localhost` base URL into every deployed image).
 */
export const dynamic = "force-dynamic"

/**
 * Paths kept out of the index.
 *
 * Two different reasons, both real:
 *  - authenticated or per-customer surfaces (`/dashboard`, `/shop/orders`, the
 *    sign-in and OIDC callback routes) have nothing a search result should ever
 *    lead to, and some of them carry PII in the URL;
 *  - mid-journey storefront steps (`/cart`, `/checkout`, an order-status page
 *    keyed by order number) are states, not destinations. Indexing them
 *    competes with the storefront page that should rank instead.
 *
 * The storefront itself — `/shop` and `/shop/<slug>` — is deliberately NOT in
 * this list. Reach is the product's value proposition to a vendor.
 */
const DISALLOW = [
  "/api/",
  "/auth/",
  "/dashboard",
  "/shop/orders",
  "/shop/signin",
  "/shop/auth/",
  "/shop/*/cart",
  "/shop/*/checkout",
  "/shop/*/orders/",
  "/track",
  "/unsubscribe",
]

export default function robots(): MetadataRoute.Robots {
  const origin = resolvePublicOrigin()

  return {
    rules: [{ userAgent: "*", allow: "/", disallow: DISALLOW }],
    // A `Sitemap:` line MUST be an absolute URL — the robots.txt format has no
    // notion of a relative one. So when no origin can be trusted the line is
    // omitted rather than emitted wrong: a robots.txt advertising a sitemap at
    // a bind address or a guessed hostname sends crawlers somewhere real and
    // wrong, which is worse than not advertising it. Every environment that
    // matters sets NEXTAUTH_URL (compose, and each k8s overlay via
    // app-config/frontend.url), so the line is present wherever it is useful.
    sitemap: origin ? `${origin}/sitemap.xml` : undefined,
  }
}
