import type { Metadata } from "next"

/**
 * The default Open Graph / Twitter image for every public page that has none of its own (SW-CLR-08,
 * swarm epic #964: "no og:image on any page").
 *
 * An EXISTING brand asset, not a new one: the owner's BeesLens photograph of a jollof rice meal box that
 * already leads the landing page (D16, PR #1103; credits in public/marketing/landing/credits.json). It is
 * served statically from /public, so there is no third-party host and nothing for the CSP to learn. Next
 * resolves the relative URL against `metadataBase`, exactly as it does the canonical: with an origin
 * configured (APP_PUBLIC_ORIGIN / NEXTAUTH_URL) the tag is absolute; with none it stays root-relative and no
 * host is guessed. A designed share card is the owner's to supply, and replacing this one file and its
 * constants is the whole change when it arrives.
 *
 * WHY A HELPER AND NOT ONLY THE ROOT LAYOUT. A page that sets its own `openGraph` replaces the parent's
 * `openGraph` wholesale (Next does not merge it), so an image declared once at the root reaches only the pages
 * that set none. The pages that matter most for sharing set one. `openGraphWithDefaultImage` is how they keep
 * the image; `app/__tests__/og-image-default.test.ts` scans the tree so a new page cannot forget.
 */
export const DEFAULT_OG_IMAGE = {
  url: "/marketing/landing/beeslens-jollof-rice-meal-box.webp",
  width: 1000,
  height: 667,
  alt: "A jollof rice meal box from a kitchen on J'Toye",
} as const

type OpenGraph = NonNullable<Metadata["openGraph"]>

/** `og` with the default image added unless it already names images of its own. */
export function openGraphWithDefaultImage(og: OpenGraph): OpenGraph {
  const own = (og as { images?: unknown }).images
  if (Array.isArray(own) ? own.length > 0 : own != null) return og
  return { ...og, images: [{ ...DEFAULT_OG_IMAGE }] } as OpenGraph
}
