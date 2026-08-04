import type { Metadata } from "next"
import { headers } from "next/headers"
import { notFound } from "next/navigation"
import { loadShopDetail } from "@/lib/storefront-server"
import { resolvePublicOrigin } from "@/lib/public-origin"
import { serialiseJsonLd, shopStructuredData } from "@/lib/structured-data"
import { ShopDetailClient } from "./shop-detail-client"

/**
 * An individual storefront — SERVER component (issues #507, #447).
 *
 * WHAT WAS WRONG. This file began with `"use client"` and fetched in a
 * `useEffect`, so the server could only ever render the loading skeleton.
 * Measured on the running stack before this change:
 *
 *   /shop/brixton-village-grill  34,419 bytes served
 *     <h1> ................................ 0
 *     "Brixton Village Grill" occurrences .. 0
 *     rel=canonical / og: / twitter: / JSON-LD ... 0 / 0 / 0 / 0
 *     <title> ............... "J'Toye — Discover Local Vendors", identical to
 *                             /shop and to every other storefront
 *
 * A crawler and a first paint both got a spinner, and every storefront shared
 * one title — so the SEO half of this was not merely missing, it was
 * unimplementable: a `"use client"` module cannot export `generateMetadata`.
 * That is why the two issues are one change and in this order.
 *
 * The interactive half moved to `shop-detail-client.tsx` unchanged. Note that
 * `"use client"` was never the problem by itself: a client component IS rendered
 * to HTML on the server. Fetching in an effect is what made that HTML empty.
 */

interface Props {
  params: Promise<{ slug: string }>
}

/**
 * Per-shop metadata.
 *
 * NO HOSTNAME IS WRITTEN HERE. `metadataBase` comes from
 * `resolvePublicOrigin()` — injected configuration (`APP_PUBLIC_ORIGIN` ->
 * `NEXTAUTH_URL`), which is already set correctly in compose and in every k8s
 * overlay. When no origin can be trusted it is left undefined and Next emits the
 * canonical as a ROOT-RELATIVE path, which every crawler resolves against the
 * document it was served from. That is a correct answer on any host, and
 * specifically not a guess at the unsettled production domain (`jtoye.co.uk`
 * has no TLS; `olajay.co.uk` does not resolve).
 */
export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { slug } = await params
  const result = await loadShopDetail(slug)
  const origin = resolvePublicOrigin()
  const metadataBase = origin ? new URL(origin) : undefined
  const canonical = `/shop/${slug}`

  // A missing shop is raised HERE, in metadata, as well as in the page body.
  //
  // WHAT THIS DOES AND DOES NOT ACHIEVE — MEASURED, NOT ASSUMED. The intent was
  // a real 404 STATUS for a dead storefront instead of a soft 404. It does not
  // get one: measured on this branch, `/shop/<missing-slug>` answers **HTTP 200**
  // whether `notFound()` is raised from the page body, from here, or from both.
  // The cause is app-wide streaming — `app/layout.tsx` sets
  // `dynamic = "force-dynamic"` for the CSP nonce, so the response is
  // `Transfer-Encoding: chunked` and its status line is committed before the
  // tree finishes rendering. Removing `app/shop/loading.tsx` (the obvious
  // suspect, since an implicit Suspense boundary would flush the shell early)
  // was tested and changed nothing — still 200. The root layout is shared with
  // the dashboard and is not this change's to alter.
  //
  // What it DOES achieve is the part that actually matters for discoverability:
  // the route renders not-found.tsx, whose metadata carries `robots: noindex`
  // and its own title. A dead storefront therefore cannot enter the index and
  // cannot compete with the live ones — which is the harm a soft 404 causes.
  // The status code remains a known gap, recorded here rather than papered over.
  if (result.state === "notfound") notFound()

  if (result.state !== "ok") {
    // Do not publish a title for a shop we could not read. `noindex` on the
    // deferred case stops a transient 429 from getting a placeholder page
    // indexed under the vendor's name.
    return {
      metadataBase,
      title: "Kitchen unavailable — J'Toye",
      description: "This kitchen could not be loaded right now.",
      robots: { index: false, follow: true },
    }
  }

  const { shop, products } = result.data
  const dishCount = Object.values(products).flat().length
  const cuisines = shop.tags?.split(",").map((t) => t.trim()).filter(Boolean) ?? []

  // A real, per-shop description built from the vendor's own copy, falling back
  // to something specific rather than to the marketing boilerplate that all four
  // public pages used to share.
  const description =
    shop.description?.trim() ||
    [
      `Order from ${shop.name}`,
      shop.address ? `in ${shop.address}` : null,
      cuisines.length > 0 ? `— ${cuisines.join(", ")}` : null,
      dishCount > 0 ? `${dishCount} dishes available for delivery and collection.` : null,
    ]
      .filter(Boolean)
      .join(" ")

  const image = shop.bannerUrl || shop.logoUrl || null

  return {
    metadataBase,
    title: `${shop.name} — order online | J'Toye`,
    description,
    keywords: cuisines.length > 0 ? cuisines : undefined,
    alternates: { canonical },
    openGraph: {
      type: "website",
      siteName: "J'Toye",
      title: `${shop.name} — order online`,
      description,
      url: canonical,
      locale: "en_GB",
      images: image ? [{ url: image, alt: `${shop.name}` }] : undefined,
    },
    twitter: {
      card: image ? "summary_large_image" : "summary",
      title: `${shop.name} — order online`,
      description,
      images: image ? [image] : undefined,
    },
  }
}

export default async function ShopDetailPage({ params }: Props) {
  const { slug } = await params
  const result = await loadShopDetail(slug)

  // A slug that does not exist answers a REAL 404 rather than a 200 carrying
  // "Shop not found" — a soft 404 keeps dead storefronts in the index. The
  // customer-facing copy is unchanged; it moved to not-found.tsx.
  if (result.state === "notfound") notFound()

  // `defer` (429 / 5xx / DNS / timeout) hands over to the island with no seed,
  // which then runs exactly the retry-and-backoff path it always did.
  const initial = result.state === "ok" ? result.data : null

  // The nonce middleware.ts put on the request. A JSON-LD data block is not
  // executable script and browsers do not CSP-check it, but carrying the nonce
  // costs nothing and keeps this consistent with every other script Next emits
  // under the enforcing nonce CSP (#89). e2e/csp-no-violations.spec.ts covers
  // this exact route.
  const nonce = (await headers()).get("x-nonce") ?? undefined
  const origin = resolvePublicOrigin()

  const jsonLd = initial
    ? shopStructuredData({
        shop: initial.shop,
        products: initial.products,
        avgRating: initial.avgRating,
        ratedCount: initial.reviews.length,
        origin,
      })
    : null

  return (
    <>
      {jsonLd && (
        <script
          type="application/ld+json"
          nonce={nonce}
          dangerouslySetInnerHTML={{ __html: serialiseJsonLd(jsonLd) }}
        />
      )}
      <ShopDetailClient slug={slug} initial={initial} />
    </>
  )
}
