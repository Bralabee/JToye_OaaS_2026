import type { Metadata } from "next"
import { storefrontStepTitle } from "@/lib/storefront-step-title"

/**
 * 37-51 (UI-SPEC F4, UXT-084): the order page's own title, "Order {number} · {Shop} — J'Toye", in
 * the server-rendered HTML, so the route announcer reads it on arrival. The page keeps setting the
 * C3 first-view variant ("Order {number} confirmed · …", 37-37) itself once the order has loaded;
 * this is the title every other view starts with. No `robots` key: the order page's indexing
 * posture (robots.txt disallow, inherited metadata) is unchanged.
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string; orderNumber: string }>
}): Promise<Metadata> {
  const { slug, orderNumber } = await params
  return { title: await storefrontStepTitle(`Order ${orderNumber}`, slug) }
}

export default function OrderLayout({ children }: { children: React.ReactNode }) {
  return children
}
