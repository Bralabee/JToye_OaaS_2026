"use client"

import { useState, useCallback } from "react"
import {
  ChevronLeft, ChevronRight, Star, Timer,
  AlertTriangle, Flame, Leaf, ShoppingBag, Plus, Minus
} from "lucide-react"
import { SafeImage } from "@/components/ui/safe-image"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { BrandPlaceholder } from "@/components/storefront/brand-placeholder"
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
    <Dialog open={isOpen} onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="max-w-lg max-h-[90vh] overflow-hidden p-0 flex flex-col">
        {/* Image carousel */}
        {images.length > 0 ? (
          <div className="relative aspect-[4/3] bg-surface-muted flex-shrink-0">
            <SafeImage
              src={images[currentImageIndex]}
              alt={`${product.title} - image ${currentImageIndex + 1}`}
              className="w-full h-full object-cover"
              loading="eager"
            />

            {/* Navigation arrows */}
            {hasMultipleImages && (
              <>
                <button
                  type="button"
                  onClick={prevImage}
                  aria-label="Previous image"
                  className="absolute left-2 top-1/2 -translate-y-1/2 bg-ink-primary/40 backdrop-blur-sm hover:bg-ink-primary/60 text-ink-inverse rounded-full p-1.5 transition-colors"
                >
                  <ChevronLeft className="h-5 w-5" />
                </button>
                <button
                  type="button"
                  onClick={nextImage}
                  aria-label="Next image"
                  className="absolute right-2 top-1/2 -translate-y-1/2 bg-ink-primary/40 backdrop-blur-sm hover:bg-ink-primary/60 text-ink-inverse rounded-full p-1.5 transition-colors"
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
                    aria-label={`Go to image ${i + 1}`}
                    className={`h-2 w-2 rounded-full transition-colors ${
                      i === currentImageIndex
                        ? "bg-surface-card"
                        : "bg-surface-card/40 hover:bg-surface-card/60"
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
                    aria-label={`Select image ${i + 1}`}
                    className={`h-10 w-10 rounded-sm overflow-hidden ring-2 transition-all ${
                      i === currentImageIndex
                        ? "ring-surface-card scale-105"
                        : "ring-transparent opacity-70 hover:opacity-100"
                    }`}
                  >
                    <SafeImage src={url} alt="" className="w-full h-full object-cover" />
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <BrandPlaceholder aspect="aspect-[4/3]" className="flex-shrink-0" />
        )}

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-5 space-y-4">
          {/* Title + price row */}
          <DialogHeader>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <DialogTitle className="font-display text-xl font-semibold tracking-tight flex items-center gap-2">
                  {product.featured && (
                    <Star className="h-4 w-4 text-warning fill-current flex-shrink-0" aria-label="Featured" />
                  )}
                  <span className="min-w-0 break-words">{product.title}</span>
                  {outOfStock && (
                    <Badge variant="danger" size="sm">Out of Stock</Badge>
                  )}
                </DialogTitle>
              </div>
              <span className="font-mono tabular-nums text-xl font-semibold text-ink-primary whitespace-nowrap">
                {formatPrice(product.pricePennies)}
              </span>
            </div>
          </DialogHeader>

          {/* Dietary tags */}
          {(dietaryTags.length > 0 || product.preparationTimeMinutes) && (
            <div className="flex flex-wrap gap-1.5">
              {dietaryTags.map((tag) => (
                <Badge key={tag} variant="success" size="sm">
                  {getDietaryIcon(tag)}
                  {tag}
                </Badge>
              ))}
              {product.preparationTimeMinutes && (
                <Badge variant="subtle" size="sm">
                  <Timer className="h-3 w-3" />
                  {product.preparationTimeMinutes} min
                </Badge>
              )}
            </div>
          )}

          {/* Description */}
          {product.description && (
            <div>
              <h3 className="text-sm font-semibold text-ink-primary mb-1">About</h3>
              <p className="text-sm text-ink-secondary leading-relaxed whitespace-pre-line">
                {product.description}
              </p>
            </div>
          )}

          {/* Ingredients */}
          {product.ingredientsText && (
            <div>
              <h3 className="text-sm font-semibold text-ink-primary mb-1">Ingredients</h3>
              <p className="text-sm text-ink-tertiary leading-relaxed">
                {product.ingredientsText}
              </p>
            </div>
          )}

          {/* Allergens */}
          {allergenList.length > 0 && (
            <div className="rounded-md bg-warning-subtle border border-subtle p-3">
              <div className="flex items-center gap-1.5 mb-2">
                <AlertTriangle className="h-4 w-4 text-ink-primary" aria-hidden="true" />
                <h3 className="text-sm font-semibold text-ink-primary">
                  Allergen Information
                </h3>
              </div>
              <div className="flex flex-wrap gap-2">
                {allergenList.map((a) => (
                  <span
                    key={a.bit}
                    className="inline-flex items-center gap-1 bg-surface-card rounded-sm border border-subtle px-2 py-1 text-xs font-medium text-ink-primary"
                  >
                    <span aria-hidden="true">{a.icon}</span>
                    {a.name}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Sticky add-to-cart footer */}
        <div className="border-t border-subtle p-4 bg-surface-card flex-shrink-0">
          {outOfStock ? (
            <Button
              disabled
              variant="subtle"
              size="lg"
              className="w-full"
            >
              Out of Stock
            </Button>
          ) : quantity === 0 ? (
            <Button
              onClick={onAdd}
              variant="primary"
              size="lg"
              className="w-full"
            >
              <ShoppingBag className="h-5 w-5" />
              Add to cart &middot;{" "}
              <span className="font-mono tabular-nums">{formatPrice(product.pricePennies)}</span>
            </Button>
          ) : (
            <div className="flex items-center justify-between">
              <span className="text-sm text-ink-tertiary">In cart</span>
              <div className="flex items-center gap-3">
                <Button
                  onClick={onDecrement}
                  variant="subtle"
                  size="iconSm"
                  aria-label="Decrease quantity"
                  className="rounded-pill"
                >
                  <Minus className="h-4 w-4" />
                </Button>
                <span className="font-mono tabular-nums text-lg font-semibold w-8 text-center">
                  {quantity}
                </span>
                <Button
                  onClick={onIncrement}
                  variant="primary"
                  size="iconSm"
                  aria-label="Increase quantity"
                  className="rounded-pill"
                >
                  <Plus className="h-4 w-4" />
                </Button>
              </div>
              <span className="font-mono tabular-nums text-sm font-semibold text-ink-primary">
                {formatPrice(product.pricePennies * quantity)}
              </span>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
