"use client"

import { use } from "react"
import { CartProvider } from "@/components/storefront/cart-provider"
import { CartDrawer } from "@/components/storefront/cart-drawer"

export default function ShopSlugLayout({
  children,
  params,
}: {
  children: React.ReactNode
  params: Promise<{ slug: string }>
}) {
  const { slug } = use(params)

  return (
    <CartProvider shopSlug={slug}>
      {children}
      {/* New slide-over basket — shares the cart context, opened by the nav
          badge via the `jtoye:cart-open` event; unmounts with the slug subtree. */}
      <CartDrawer />
    </CartProvider>
  )
}
