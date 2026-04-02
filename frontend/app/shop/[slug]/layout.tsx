"use client"

import { use } from "react"
import { CartProvider } from "@/components/storefront/cart-provider"

export default function ShopSlugLayout({
  children,
  params,
}: {
  children: React.ReactNode
  params: Promise<{ slug: string }>
}) {
  const { slug } = use(params)

  return <CartProvider shopSlug={slug}>{children}</CartProvider>
}
