import type { Metadata } from "next"
import { storefrontStepTitle } from "@/lib/storefront-step-title"

/**
 * 37-51 (UI-SPEC F4, UXT-084): the checkout's own title, "Checkout · {Shop} — J'Toye", so the
 * route announcer says where the customer is. The page is a client component and cannot export
 * metadata; this layout is a server module and renders nothing of its own. No `robots` key: the
 * checkout's indexing posture (robots.txt disallow, inherited metadata) is unchanged.
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>
}): Promise<Metadata> {
  const { slug } = await params
  return { title: await storefrontStepTitle("Checkout", slug) }
}

export default function CheckoutLayout({ children }: { children: React.ReactNode }) {
  return children
}
