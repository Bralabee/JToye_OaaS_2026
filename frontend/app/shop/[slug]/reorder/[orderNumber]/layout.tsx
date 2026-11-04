import type { Metadata } from "next"
import { storefrontStepTitle } from "@/lib/storefront-step-title"

/**
 * "Order again" (#845): a title for the route announcer ("Order again · {Shop} — J'Toye"), and noindex.
 *
 * The page is a client component and cannot export metadata; this layout is a server module and renders
 * nothing of its own (the same shape as the basket's). It is a per-customer action reached from a signed-in
 * page, a state and not a destination, so it is kept out of the index in the metadata as well as in
 * `app/robots.ts` (`/shop/*\/reorder/`). The title carries only the shop's name, which the page shows.
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>
}): Promise<Metadata> {
  const { slug } = await params
  return { title: await storefrontStepTitle("Order again", slug), robots: { index: false, follow: false } }
}

export default function ReorderLayout({ children }: { children: React.ReactNode }) {
  return children
}
