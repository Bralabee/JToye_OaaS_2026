"use client"

import { useState, useCallback } from "react"
import {
  X, ChevronLeft, ChevronRight, Star, Timer,
  AlertTriangle, Flame, Leaf, ShoppingBag, Plus, Minus
} from "lucide-react"
import { SafeImage } from "@/components/ui/safe-image"
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

  if (!isOpen) return null

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm"
        onClick={onClose}
      />

      {/* Modal */}
      <div
        className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4"
        onClick={onClose}
      >
        <div
          className="relative w-full max-w-lg bg-white rounded-t-2xl sm:rounded-2xl shadow-2xl max-h-[90vh] overflow-hidden flex flex-col"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Close button */}
          <button
            onClick={onClose}
            className="absolute top-3 right-3 z-10 bg-black/30 backdrop-blur-sm hover:bg-black/50 text-white rounded-full p-1.5 transition-colors"
          >
            <X className="h-5 w-5" />
          </button>

          {/* Image carousel */}
          {images.length > 0 ? (
            <div className="relative aspect-[4/3] bg-slate-100 flex-shrink-0">
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
                    onClick={prevImage}
                    className="absolute left-2 top-1/2 -translate-y-1/2 bg-black/30 backdrop-blur-sm hover:bg-black/50 text-white rounded-full p-1.5 transition-colors"
                  >
                    <ChevronLeft className="h-5 w-5" />
                  </button>
                  <button
                    onClick={nextImage}
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
                      onClick={() => setCurrentImageIndex(i)}
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
                      onClick={() => setCurrentImageIndex(i)}
                      className={`h-10 w-10 rounded-lg overflow-hidden ring-2 transition-all ${
                        i === currentImageIndex
                          ? "ring-white scale-105"
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
            <div className="aspect-[4/3] bg-gradient-to-br from-slate-100 to-slate-50 flex items-center justify-center flex-shrink-0">
              <ShoppingBag className="h-16 w-16 text-slate-200" />
            </div>
          )}

          {/* Content */}
          <div className="flex-1 overflow-y-auto p-5 space-y-4">
            {/* Title + price row */}
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h2 className="text-xl font-bold text-slate-900 flex items-center gap-2">
                  {product.featured && (
                    <Star className="h-4 w-4 text-amber-500 fill-amber-500 flex-shrink-0" />
                  )}
                  {product.title}
                  {outOfStock && (
                    <Badge variant="destructive" className="text-xs">Out of Stock</Badge>
                  )}
                </h2>
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
                  <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 text-slate-600 px-2.5 py-1 text-xs font-medium">
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
                <p className="text-sm text-slate-600 leading-relaxed whitespace-pre-line">
                  {product.description}
                </p>
              </div>
            )}

            {/* Ingredients */}
            {product.ingredientsText && (
              <div>
                <h3 className="text-sm font-semibold text-slate-700 mb-1">Ingredients</h3>
                <IngredientText
                  text={product.ingredientsText}
                  className="block text-sm text-slate-500 leading-relaxed"
                />
              </div>
            )}

            {/* Allergens */}
            {allergenList.length > 0 && (
              <div className="rounded-lg bg-amber-50 border border-amber-200 p-3">
                <div className="flex items-center gap-1.5 mb-2">
                  <AlertTriangle className="h-4 w-4 text-amber-600" />
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
          <div className="border-t border-slate-100 p-4 bg-white flex-shrink-0">
            {outOfStock ? (
              <button
                disabled
                className="w-full flex items-center justify-center gap-2 rounded-xl bg-slate-300 text-slate-500 font-semibold py-3 px-4 cursor-not-allowed"
              >
                Out of Stock
              </button>
            ) : quantity === 0 ? (
              <button
                onClick={onAdd}
                className="w-full flex items-center justify-center gap-2 rounded-xl bg-orange-500 hover:bg-orange-600 text-white font-semibold py-3 px-4 transition-all active:scale-[0.98]"
              >
                <ShoppingBag className="h-5 w-5" />
                Add to cart &middot; {formatPrice(product.pricePennies)}
              </button>
            ) : (
              <div className="flex items-center justify-between">
                <span className="text-sm text-slate-500">In cart</span>
                <div className="flex items-center gap-3">
                  <button
                    onClick={onDecrement}
                    className="h-10 w-10 rounded-full bg-slate-100 hover:bg-slate-200 flex items-center justify-center transition-all active:scale-95"
                  >
                    <Minus className="h-4 w-4" />
                  </button>
                  <span className="text-lg font-bold w-8 text-center">{quantity}</span>
                  <button
                    onClick={onIncrement}
                    className="h-10 w-10 rounded-full bg-orange-500 hover:bg-orange-600 text-white flex items-center justify-center transition-all active:scale-95"
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
        </div>
      </div>
    </>
  )
}
