import type { Metadata } from "next"
import { headers } from "next/headers"
import { loadShopList } from "@/lib/storefront-server"
import {
  SEARCH_INTERPRETATION_HEADER,
  parseSearchInterpretation,
} from "@/lib/search-interpretation"
import { resolvePublicOrigin } from "@/lib/public-origin"
import { serialiseJsonLd, shopListStructuredData } from "@/lib/structured-data"
import { ShopDiscoveryClient, SHOPS_PAGE_SIZE } from "./shop-discovery-client"

/**
 * The storefront directory — SERVER component (issues #507, #447).
 *
 * WHY IT CHANGED. This is the first page every customer sees and the entry point
 * to every vendor, and it was `"use client"` with the catalogue fetched in a
 * `useEffect`. Measured on the running stack before this change, `/shop` served
 * 36,829 bytes containing **zero** occurrences of any shop's name — the HTML was
 * a skeleton grid and nothing else. #463 had cited this page as its
 * "server-rendered control" at 12 ms; that 12 ms was the shell, not the content,
 * which is the premise correction #507 was filed to record.
 *
 * `"use client"` was never the problem on its own — a client component IS
 * rendered to HTML by the server. Fetching in an effect is what guaranteed the
 * HTML was empty, because an effect only ever runs in a browser.
 */

export async function generateMetadata(): Promise<Metadata> {
  const origin = resolvePublicOrigin()
  const description =
    "Browse independent local kitchens on J'Toye — see menus, allergen information " +
    "and delivery options, and order directly from the vendor."

  return {
    // Injected, never literal. Absent -> Next emits a root-relative canonical,
    // which resolves against whatever host actually served the page. See
    // lib/public-origin.ts for why this is NEXTAUTH_URL and not a NEXT_PUBLIC_*
    // (those are inlined at BUILD time and cannot be corrected per environment).
    metadataBase: origin ? new URL(origin) : undefined,
    title: "Local kitchens near you — order online | J'Toye",
    description,
    alternates: { canonical: "/shop" },
    openGraph: {
      type: "website",
      siteName: "J'Toye",
      title: "Local kitchens near you — order online",
      description,
      url: "/shop",
      locale: "en_GB",
    },
    twitter: {
      card: "summary",
      title: "Local kitchens near you — order online",
      description,
    },
  }
}

export default async function ShopDiscoveryPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string | string[] }>
}) {
  const params = await searchParams
  // `?q=` can legally arrive repeated; the page has always treated it as one
  // term, so take the first rather than joining them into a nonsense query.
  const raw = Array.isArray(params.q) ? params.q[0] : params.q
  const q = (raw ?? "").trim()

  const result = await loadShopList({ page: 0, size: SHOPS_PAGE_SIZE, q })
  const initial = result.state === "ok" ? result.data : null

  // The SSR seed carries the server's own reading of `q`, so the FIRST PAINT of
  // /shop?q=SE22 is already honest. Without it the island's `serverSeeded` ref
  // suppresses the mount fetch, the plain heading renders over a proximity
  // result, and nothing ever corrects it. A deferred load carries no reading,
  // which degrades to `text` — a non-answer makes no claim (CA-I).
  const initialInterpretation = parseSearchInterpretation(
    result.state === "ok" ? result.headers?.get(SEARCH_INTERPRETATION_HEADER) : null
  )

  const nonce = (await headers()).get("x-nonce") ?? undefined
  const origin = resolvePublicOrigin()
  const jsonLd = initial ? shopListStructuredData(initial.content ?? [], origin) : null

  return (
    <>
      {jsonLd && (
        <script
          type="application/ld+json"
          nonce={nonce}
          dangerouslySetInnerHTML={{ __html: serialiseJsonLd(jsonLd) }}
        />
      )}
      <ShopDiscoveryClient
        initial={initial}
        initialQuery={q}
        initialInterpretation={initialInterpretation}
      />
    </>
  )
}
