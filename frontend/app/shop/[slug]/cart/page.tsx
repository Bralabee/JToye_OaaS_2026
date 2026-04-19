"use client"

import { use } from "react"
import Link from "next/link"
import { motion } from "framer-motion"
import { ArrowLeft, Minus, Plus, Trash2, ShoppingBag } from "lucide-react"
import { useCart } from "@/components/storefront/cart-provider"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { BrandPlaceholder } from "@/components/storefront/brand-placeholder"
import { fadeUp, useReducedMotionSafe } from "@/lib/motion"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

export default function CartPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params)
  const { items, updateQuantity, removeItem, clearCart, itemCount, totalPennies } = useCart()
  const pageVariants = useReducedMotionSafe(fadeUp)

  if (items.length === 0) {
    return (
      <div className="bg-surface-canvas min-h-screen">
        <motion.div
          variants={pageVariants}
          initial="hidden"
          animate="visible"
          className="mx-auto max-w-2xl px-4 py-16 text-center"
        >
          <ShoppingBag className="mx-auto h-16 w-16 text-ink-quaternary" strokeWidth={1.5} />
          <h2 className="mt-4 font-display text-display-sm font-medium tracking-tight text-ink-primary">
            Your basket is empty
          </h2>
          <p className="mt-2 text-body-sm text-ink-secondary">
            Add items from the menu to get started.
          </p>
          <div className="mt-6">
            <Button asChild variant="primary" size="lg" className="rounded-pill">
              <Link href={`/shop/${slug}`}>
                <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
                Back to menu
              </Link>
            </Button>
          </div>
        </motion.div>
      </div>
    )
  }

  return (
    <div className="bg-surface-canvas min-h-screen">
      <motion.div
        variants={pageVariants}
        initial="hidden"
        animate="visible"
        className="mx-auto max-w-2xl px-4 sm:px-6 py-6"
      >
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <Link
              href={`/shop/${slug}`}
              className="inline-flex items-center gap-1 text-caption text-ink-tertiary hover:text-ink-primary transition-colors duration-fast mb-1"
            >
              <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
              Back to menu
            </Link>
            <h1 className="font-display text-display-sm font-medium tracking-tight text-ink-primary">
              Your basket
            </h1>
            <p className="text-body-sm text-ink-secondary">
              {itemCount} item{itemCount !== 1 ? "s" : ""}
            </p>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={clearCart}
            className="text-ink-tertiary hover:text-danger"
          >
            Clear all
          </Button>
        </div>

        {/* Items */}
        <div className="space-y-3">
          {items.map((item) => (
            <Card key={item.productId} variant="default" className="p-3">
              <div className="flex items-center gap-3">
                {/* Image or placeholder */}
                {item.imageUrl ? (
                  <div className="h-16 w-16 flex-shrink-0 rounded-md overflow-hidden">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={item.imageUrl}
                      alt={item.title}
                      className="h-full w-full object-cover"
                    />
                  </div>
                ) : (
                  <div className="h-16 w-16 flex-shrink-0 rounded-md overflow-hidden">
                    <BrandPlaceholder aspect="aspect-square" className="h-full w-full" />
                  </div>
                )}

                {/* Details */}
                <div className="flex-1 min-w-0">
                  <h3 className="text-body-sm font-semibold text-ink-primary truncate">
                    {item.title}
                  </h3>
                  {item.category && (
                    <p className="text-[10px] uppercase tracking-widest text-ink-tertiary">
                      {item.category}
                    </p>
                  )}
                  <p className="mt-0.5 font-mono tabular-nums text-body-sm font-semibold text-ink-primary">
                    {formatPrice(item.pricePennies * item.quantity)}
                  </p>
                </div>

                {/* Quantity controls */}
                <div className="flex items-center gap-0">
                  <button
                    type="button"
                    aria-label={item.quantity === 1 ? "Remove item" : "Decrease quantity"}
                    onClick={() => {
                      if (item.quantity === 1) {
                        removeItem(item.productId)
                      } else {
                        updateQuantity(item.productId, item.quantity - 1)
                      }
                    }}
                    className="flex h-8 w-8 items-center justify-center rounded-pill border border-border-tone text-ink-secondary hover:bg-surface-subtle active:scale-95 transition-all duration-fast motion-reduce:transition-none"
                  >
                    {item.quantity === 1 ? (
                      <Trash2 className="h-3.5 w-3.5 text-danger" strokeWidth={1.5} />
                    ) : (
                      <Minus className="h-3.5 w-3.5" strokeWidth={1.5} />
                    )}
                  </button>
                  <span className="min-w-[2rem] text-center font-mono tabular-nums text-body-sm font-semibold text-ink-primary">
                    {item.quantity}
                  </span>
                  <button
                    type="button"
                    aria-label="Increase quantity"
                    onClick={() => updateQuantity(item.productId, item.quantity + 1)}
                    className="flex h-8 w-8 items-center justify-center rounded-pill border border-border-tone text-ink-secondary hover:bg-surface-subtle active:scale-95 transition-all duration-fast motion-reduce:transition-none"
                  >
                    <Plus className="h-3.5 w-3.5" strokeWidth={1.5} />
                  </button>
                </div>
              </div>
            </Card>
          ))}
        </div>

        {/* Order summary */}
        <Card variant="lifted" className="mt-6 p-4">
          <CardContent className="p-0 space-y-0">
            <div className="flex items-center justify-between">
              <span className="text-body-sm text-ink-secondary">Subtotal</span>
              <span className="font-mono tabular-nums text-body-sm font-semibold text-ink-primary">
                {formatPrice(totalPennies)}
              </span>
            </div>
            <div className="mt-4 border-t border-border-tone-subtle pt-4 flex items-center justify-between">
              <span className="font-display text-body-lg font-semibold text-ink-primary">Total</span>
              <span className="font-mono tabular-nums text-body-lg font-semibold text-ink-primary">
                {formatPrice(totalPennies)}
              </span>
            </div>
          </CardContent>
        </Card>

        {/* Actions */}
        <div className="mt-6 space-y-3">
          <Button asChild variant="primary" size="lg" className="w-full rounded-pill shadow-lift">
            <Link href={`/shop/${slug}/checkout`}>
              <span>Proceed to checkout</span>
              <span className="font-mono tabular-nums text-ink-on-brand/80">
                {formatPrice(totalPennies)}
              </span>
            </Link>
          </Button>
          <Button asChild variant="secondary" size="lg" className="w-full rounded-pill">
            <Link href={`/shop/${slug}`}>
              <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
              Add more items
            </Link>
          </Button>
        </div>
      </motion.div>
    </div>
  )
}
