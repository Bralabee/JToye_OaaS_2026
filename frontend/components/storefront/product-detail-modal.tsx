"use client"

import { useState, useCallback, useRef } from "react"
import * as DialogPrimitive from "@radix-ui/react-dialog"
import {
  X, ChevronLeft, ChevronRight, Star, Timer,
  AlertTriangle, Flame, Leaf, ShoppingBag, Plus, Minus
} from "lucide-react"
import { SafeImage } from "@/components/ui/safe-image"
import { AspectFrame } from "@/components/ui/aspect-frame"
import { IngredientText } from "@/components/ui/ingredient-text"
import { Badge } from "@/components/ui/badge"
import { PublicProduct } from "@/types/storefront"
import { ALLERGENS, hasAllergen } from "@/types/api"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

function getDietaryIcon(tag: string) {
  const t = tag.toLowerCase().trim()
  if (t.includes("vegan")) return <Leaf className="h-3 w-3" />
  if (t.includes("spicy") || t.includes("hot")) return <Flame className="h-3 w-3" />
  if (t.includes("vegetarian")) return <Leaf className="h-3 w-3" />
  return null
}

interface ProductDetailModalProps {
  product: PublicProduct
  isOpen: boolean
  onClose: () => void
  quantity: number
  onAdd: () => void
  onIncrement: () => void
  onDecrement: () => void
}

export function ProductDetailModal({
  product,
  isOpen,
  onClose,
  quantity,
  onAdd,
  onIncrement,
  onDecrement,
}: ProductDetailModalProps) {
  const [currentImageIndex, setCurrentImageIndex] = useState(0)
  // Whoever had focus when the dialog opened — see onCloseAutoFocus below.
  const openerRef = useRef<HTMLElement | null>(null)

  const images = product.imageUrls?.length > 0
    ? product.imageUrls
    : product.imageUrl
      ? [product.imageUrl]
      : []

  const hasMultipleImages = images.length > 1

  const nextImage = useCallback(() => {
    setCurrentImageIndex((i) => (i + 1) % images.length)
  }, [images.length])

  const prevImage = useCallback(() => {
    setCurrentImageIndex((i) => (i - 1 + images.length) % images.length)
  }, [images.length])

  const outOfStock = product.inStock === false
  const allergenList = ALLERGENS.filter((a) => hasAllergen(product.allergenMask, a.bit))
  const dietaryTags = product.dietaryTags
    ?.split(",")
    .map((t) => t.trim())
    .filter(Boolean) || []

  return (
    <DialogPrimitive.Root
      open={isOpen}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogPrimitive.Portal>
        {/* The BACKDROP and the CENTRING BOX are one element now.
            Previously they were two sibling `fixed inset-0 z-50` divs, which is
            what made this the only hand-rolled overlay in the repo (#446/#272):
            neither carried a dialog role, so there was no Escape handling, no
            focus trap, no focus restore and no scroll lock. Radix supports
            Content nested inside Overlay, and merging them keeps the rendered
            geometry byte-identical (same classes, same padding, same z-index)
            while the outside-click dismiss that the old wrapper's `onClick`
            provided is now Radix's own `onPointerDownOutside`.

            Deliberately built on the PRIMITIVES rather than `@/components/ui/dialog`:
            that wrapper hard-codes a centred `translate` panel plus
            `data-[state]:animate-in/out`, so adopting it would both break the
            mobile bottom-sheet layout and introduce motion this port must not
            change. */}
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-end sm:items-center justify-center p-0 sm:p-4">
          <DialogPrimitive.Content
            /* Radix inerts the rest of the page with `hideOthers()` (aria-hidden
               on every outside node) and does NOT emit aria-modal. Both are
               legitimate, and screen readers honour aria-modal, so state it
               explicitly rather than relying on one mechanism. */
            aria-modal="true"
            onOpenAutoFocus={() => {
              // Runs BEFORE Radix moves focus into the panel, so this is still
              // the element that opened the dialog.
              openerRef.current = document.activeElement as HTMLElement | null
            }}
            onCloseAutoFocus={(event) => {
              // Radix's modal default is preventDefault() + focus its own
              // <Dialog.Trigger>. This dialog is driven by a controlled `isOpen`
              // prop instead of a Radix Trigger, so that ref is null and focus
              // was measured landing on <body> after Escape — a keyboard user
              // loses their place in the menu. Restore the opener explicitly.
              event.preventDefault()
              const opener = openerRef.current
              if (opener && opener.isConnected) opener.focus()
            }}
            className="relative w-full max-w-lg bg-white rounded-t-2xl sm:rounded-2xl shadow-2xl max-h-[90vh] overflow-hidden flex flex-col focus:outline-none"
          >
          {/* Close button */}
          <DialogPrimitive.Close
            className="absolute top-3 right-3 z-10 bg-black/30 backdrop-blur-sm hover:bg-black/50 text-white rounded-full p-1.5 transition-colors"
          >
            <X className="h-5 w-5" />
            {/* The control was previously an unnamed icon-only button. */}
            <span className="sr-only">Close</span>
          </DialogPrimitive.Close>

          {/* Image carousel. The fixed-ratio window comes from AspectFrame —
              see the note there for why hand-rolling it silently produced a
              modal that changed shape with every photo. Overlays ride as
              children and position against the frame. */}
          {images.length > 0 ? (
            <AspectFrame
              ratio="4/3"
              src={images[currentImageIndex]}
              alt={`${product.title} - image ${currentImageIndex + 1}`}
              loading="eager"
              className="bg-cream flex-shrink-0"
            >
              {/* Navigation arrows */}
              {hasMultipleImages && (
                <>
                  <button
                    type="button"
                    onClick={prevImage}
                    aria-label="Previous image"
                    className="absolute left-2 top-1/2 -translate-y-1/2 bg-black/30 backdrop-blur-sm hover:bg-black/50 text-white rounded-full p-1.5 transition-colors"
                  >
                    <ChevronLeft className="h-5 w-5" />
                  </button>
                  <button
                    type="button"
                    onClick={nextImage}
                    aria-label="Next image"
                    className="absolute right-2 top-1/2 -translate-y-1/2 bg-black/30 backdrop-blur-sm hover:bg-black/50 text-white rounded-full p-1.5 transition-colors"
                  >
                    <ChevronRight className="h-5 w-5" />
                  </button>
                </>
              )}

              {/* Dot indicators */}
              {hasMultipleImages && (
                <div className="absolute bottom-3 left-1/2 -translate-x-1/2 flex gap-1.5">
                  {images.map((_, i) => (
                    <button
                      key={i}
                      type="button"
                      onClick={() => setCurrentImageIndex(i)}
                      aria-label={`Show image ${i + 1} of ${images.length}`}
                      aria-current={i === currentImageIndex}
                      className={`h-2 w-2 rounded-full transition-colors ${
                        i === currentImageIndex
                          ? "bg-white"
                          : "bg-white/40 hover:bg-white/60"
                      }`}
                    />
                  ))}
                </div>
              )}

              {/* Thumbnail strip */}
              {hasMultipleImages && (
                <div className="absolute bottom-10 left-1/2 -translate-x-1/2 flex gap-1.5">
                  {images.map((url, i) => (
                    <button
                      key={i}
                      type="button"
                      onClick={() => setCurrentImageIndex(i)}
                      aria-label={`Show image ${i + 1} of ${images.length}`}
                      className={`h-10 w-10 rounded-lg overflow-hidden ring-2 transition-all ${
                        i === currentImageIndex
                          ? "ring-white scale-105"
                          : "ring-transparent opacity-70 hover:opacity-100"
                      }`}
                    >
                      {/* Definite-height box (h-10), so h-full resolves. */}
                      <SafeImage src={url} alt="" className="w-full h-full object-cover" />
                    </button>
                  ))}
                </div>
              )}
            </AspectFrame>
          ) : (
            // Same window with no image: AspectFrame renders SafeImage's
            // fallback, so the placeholder cannot drift from the real frame.
            <AspectFrame
              ratio="4/3"
              src={null}
              alt=""
              className="bg-gradient-to-br from-cream-100 to-cream flex-shrink-0"
              fallbackIcon={<ShoppingBag className="h-16 w-16 text-oxblood/25" />}
            />
          )}

          {/* Content */}
          <div className="flex-1 overflow-y-auto p-5 space-y-4">
            {/* Title + price row */}
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                {/* Renders the same <h2>; being the Radix Title is what wires
                    the dialog's aria-labelledby to the dish name. */}
                <DialogPrimitive.Title className="text-xl font-bold text-slate-900 flex items-center gap-2">
                  {product.featured && (
                    <Star className="h-4 w-4 text-amber-500 fill-amber-500 flex-shrink-0" />
                  )}
                  {product.title}
                  {outOfStock && (
                    <Badge variant="destructive" className="text-xs">Out of Stock</Badge>
                  )}
                </DialogPrimitive.Title>
              </div>
              <span className="text-xl font-bold text-slate-900 whitespace-nowrap">
                {formatPrice(product.pricePennies)}
              </span>
            </div>

            {/* Dietary tags */}
            {dietaryTags.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {dietaryTags.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center gap-1 rounded-full bg-emerald-50 text-emerald-700 px-2.5 py-1 text-xs font-medium"
                  >
                    {getDietaryIcon(tag)}
                    {tag}
                  </span>
                ))}
                {product.preparationTimeMinutes && (
                  <span className="inline-flex items-center gap-1 rounded-full bg-cream text-oxblood-600 px-2.5 py-1 text-xs font-medium">
                    <Timer className="h-3 w-3" />
                    {product.preparationTimeMinutes} min
                  </span>
                )}
              </div>
            )}

            {/* Description */}
            {product.description && (
              <div>
                <h3 className="text-sm font-semibold text-slate-700 mb-1">About</h3>
                {/* asChild keeps the exact same <p>; it just becomes the node
                    the dialog's aria-describedby points at. Radix 1.1.23 tracks
                    description PRESENCE, so omitting it (no product description)
                    simply leaves aria-describedby unset — no console warning. */}
                <DialogPrimitive.Description asChild>
                  <p className="text-sm text-slate-600 leading-relaxed whitespace-pre-line">
                    {product.description}
                  </p>
                </DialogPrimitive.Description>
              </div>
            )}

            {/* Ingredients */}
            {product.ingredientsText && (
              <div>
                <h3 className="text-sm font-semibold text-slate-700 mb-1">Ingredients</h3>
                <IngredientText
                  text={product.ingredientsText}
                  className="block text-sm text-slate-600 leading-relaxed"
                />
              </div>
            )}

            {/* Allergens */}
            {allergenList.length > 0 && (
              <div className="rounded-lg bg-amber-50 border border-amber-200 p-3">
                <div className="flex items-center gap-1.5 mb-2">
                  <AlertTriangle className="h-4 w-4 text-amber-700" />
                  <h3 className="text-sm font-semibold text-amber-800">
                    Allergen Information
                  </h3>
                </div>
                <div className="flex flex-wrap gap-2">
                  {allergenList.map((a) => (
                    <span
                      key={a.bit}
                      className="inline-flex items-center gap-1 bg-white rounded-md border border-amber-200 px-2 py-1 text-xs font-medium text-amber-700"
                    >
                      {a.name}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* Sticky add-to-cart footer */}
          <div className="border-t border-cream-100 p-4 bg-white flex-shrink-0">
            {outOfStock ? (
              <button
                disabled
                className="w-full flex items-center justify-center gap-2 rounded-xl bg-slate-300 text-slate-600 font-semibold py-3 px-4 cursor-not-allowed"
              >
                Out of Stock
              </button>
            ) : quantity === 0 ? (
              <button
                onClick={onAdd}
                className="w-full flex items-center justify-center gap-2 rounded-full bg-amber-500 hover:bg-amber-400 text-amber-ink font-semibold py-3 px-4 transition-all active:scale-[0.98]"
              >
                <ShoppingBag className="h-5 w-5" />
                Add to cart &middot; {formatPrice(product.pricePennies)}
              </button>
            ) : (
              <div className="flex items-center justify-between">
                <span className="text-sm text-slate-600">In cart</span>
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={onDecrement}
                    aria-label={`Remove one ${product.title} from cart`}
                    className="h-10 w-10 rounded-full bg-cream hover:bg-cream-100 flex items-center justify-center transition-all active:scale-95"
                  >
                    <Minus className="h-4 w-4" />
                  </button>
                  <span className="text-lg font-bold w-8 text-center">{quantity}</span>
                  <button
                    type="button"
                    onClick={onIncrement}
                    aria-label={`Add one more ${product.title} to cart`}
                    className="h-10 w-10 rounded-full bg-amber-500 hover:bg-amber-400 text-amber-ink flex items-center justify-center transition-all active:scale-95"
                  >
                    <Plus className="h-4 w-4" />
                  </button>
                </div>
                <span className="text-sm font-semibold text-slate-900">
                  {formatPrice(product.pricePennies * quantity)}
                </span>
              </div>
            )}
          </div>
          </DialogPrimitive.Content>
        </DialogPrimitive.Overlay>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
