"use client"

import { useEffect, useState, useRef, useMemo, useCallback } from "react"
import Link from "next/link"
import {
  MapPin, Clock, Phone, Mail, ArrowLeft, Store,
  Flame, Leaf, Star, Timer, ChevronRight, AlertTriangle,
  Plus as PlusIcon, Minus, UtensilsCrossed, Loader2
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import {
  isRateLimitError,
  getRetryDelayMs,
  MAX_RETRY_ATTEMPTS,
} from "@/lib/public-fetch-retry"
import { DAY_LABELS, DAY_ORDER, isOpenNow } from "@/lib/opening-hours"
import { PublicShop, PublicProduct, ProductsByCategory, Review, ShopDetail } from "@/types/storefront"
import type { PublicPromotion, PublicAnnouncement } from "@/types/storefront"
import { ALLERGENS, hasAllergen } from "@/types/api"
import { useCart } from "@/components/storefront/cart-provider"
import { FloatingCartBar } from "@/components/storefront/floating-cart-bar"
import { SafeImage } from "@/components/ui/safe-image"
import { Badge } from "@/components/ui/badge"
import { ProductDetailModal } from "@/components/storefront/product-detail-modal"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

function DietaryBadge({ tag }: { tag: string }) {
  const t = tag.toLowerCase().trim()
  if (t.includes("vegan")) return <span className="inline-flex items-center gap-0.5 rounded-md bg-emerald-50 px-1.5 py-0.5 text-xs font-semibold text-emerald-700 ring-1 ring-emerald-200"><Leaf className="h-2.5 w-2.5" />Vegan</span>
  if (t.includes("vegetarian")) return <span className="inline-flex items-center gap-0.5 rounded-md bg-green-50 px-1.5 py-0.5 text-xs font-semibold text-green-700 ring-1 ring-green-200"><Leaf className="h-2.5 w-2.5" />Vegetarian</span>
  if (t.includes("spicy")) return <span className="inline-flex items-center gap-0.5 rounded-md bg-red-50 px-1.5 py-0.5 text-xs font-semibold text-red-700 ring-1 ring-red-200"><Flame className="h-2.5 w-2.5" />Spicy</span>
  if (t.includes("gluten")) return <span className="inline-flex items-center gap-0.5 rounded-md bg-amber-50 px-1.5 py-0.5 text-xs font-semibold text-amber-700 ring-1 ring-amber-200">GF</span>
  if (t.includes("halal")) return <span className="inline-flex items-center gap-0.5 rounded-md bg-teal-50 px-1.5 py-0.5 text-xs font-semibold text-teal-700 ring-1 ring-teal-200">Halal</span>
  return <span className="inline-flex items-center rounded-md bg-cream px-1.5 py-0.5 text-xs font-medium text-oxblood-600 ring-1 ring-cream-100">{tag.trim()}</span>
}

/**
 * `inFeaturedRail`: a featured dish renders twice (the Popular rail and its
 * category list). The rail copy suffixes its add-to-basket name so the two
 * controls do not share one accessible name (A11Y-4).
 */
function ProductCard({
  product,
  promo,
  inFeaturedRail = false,
}: {
  product: PublicProduct
  promo?: PublicPromotion
  inFeaturedRail?: boolean
}) {
  const [modalOpen, setModalOpen] = useState(false)
  const { addItem, items, updateQuantity } = useCart()
  const dietaryTags = product.dietaryTags?.split(",").filter(Boolean) || []
  const allergenList = ALLERGENS.filter(a => hasAllergen(product.allergenMask, a.bit))
  const cartItem = items.find((i) => i.productId === product.id)
  const quantity = cartItem?.quantity || 0

  const images = product.imageUrls?.length > 0 ? product.imageUrls : (product.imageUrl ? [product.imageUrl] : [])
  const primaryImage = images[0] || null
  const hasMultipleImages = images.length > 1

  const outOfStock = product.inStock === false

  const handleAddToCart = (e?: React.MouseEvent) => {
    e?.stopPropagation()
    if (outOfStock) return
    addItem({
      productId: product.id,
      title: product.title,
      pricePennies: product.pricePennies,
      imageUrl: product.imageUrl,
      category: product.category,
      // COR-6: carry the rate into the basket so checkout previews the VAT the server will
      // actually charge, instead of assuming 20% on a zero-rated cold-food basket.
      vatRate: product.vatRate ?? null,
    })
  }

  return (
    <>
      {/* No `onClick` on the <article> itself. The stretched trigger button at
          the bottom of this card (#446, see its comment) already covers the
          whole surface and calls `stopPropagation()`, and the two `z-10`
          controls shield themselves — so an article-level handler could never
          fire on any path, while costing a real keyboard defect:
          `jsx-a11y/click-events-have-key-events` +
          `no-noninteractive-element-interactions` (31-02 / LGL-02). Mouse
          behaviour is byte-for-byte what it was; the trigger is what opens the
          modal, as the e2e dialog spec already asserts. */}
      <article className="group relative bg-white rounded-xl border border-cream-100 overflow-hidden transition-all hover:shadow-md hover:border-amber-200 cursor-pointer active:scale-[0.99]">
        <div className="flex gap-0">
          {/* Content */}
          <div className="flex-1 p-3 sm:p-4 min-w-0">
            <div className="min-w-0">
              {/* h3, not h4 (#447). The document went H1 (shop name) -> H2
                  (category) -> H4 (dish), skipping a level: a screen-reader
                  user navigating by heading gets a gap where the menu items
                  are. Nothing visual changes — the size is set by the class,
                  not the tag. */}
              <h3 className="text-sm font-semibold text-slate-900 leading-tight truncate">
                {product.featured && <Star className="inline h-3 w-3 text-amber-500 fill-amber-500 mr-1 -mt-0.5" />}
                {product.title}
                {outOfStock && (
                  <Badge variant="destructive" className="ml-1.5 text-xs px-1.5 py-0 align-middle">Out of Stock</Badge>
                )}
              </h3>
              {product.description && (
                <p className="mt-0.5 text-xs text-slate-600 line-clamp-2 leading-relaxed">
                  {product.description}
                </p>
              )}
            </div>

            {/* Dietary tags */}
            {dietaryTags.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1">
                {dietaryTags.map((tag) => (
                  <DietaryBadge key={tag} tag={tag} />
                ))}
              </div>
            )}

            {/* Bottom row */}
            <div className="mt-2.5 flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <span className="text-sm font-bold text-slate-900">
                  {formatPrice(product.pricePennies)}
                </span>
                {product.preparationTimeMinutes && (
                  <span className="inline-flex items-center gap-0.5 text-xs text-slate-400">
                    <Timer className="h-2.5 w-2.5" />
                    {product.preparationTimeMinutes}min
                  </span>
                )}
                {allergenList.length > 0 && (
                  <span className="inline-flex items-center gap-0.5 text-xs text-amber-700">
                    <AlertTriangle className="h-2.5 w-2.5" />
                    {allergenList.length}
                  </span>
                )}
              </div>
              {/* Add to cart / quantity controls */}
              {outOfStock ? (
                <span className="inline-flex items-center rounded-full bg-slate-200 px-3 py-1 text-xs font-semibold text-slate-600 cursor-not-allowed">
                  Unavailable
                </span>
              ) : quantity === 0 ? (
                <button
                  onClick={(e) => handleAddToCart(e)}
                  // A11Y-4 (QA council 20260902-134741; WCAG 2.4.6): nine cards on one page
                  // exposed the identical name "Add", and a name-driven actor added the
                  // wrong dish. Visible text stays "Add"; the name says which dish, the
                  // same way the +/- stepper beside it already does.
                  aria-label={`Add ${product.title} to basket${inFeaturedRail ? " (featured)" : ""}`}
                  className="relative z-10 inline-flex items-center gap-1 rounded-full bg-amber-500 px-3 py-1 text-xs font-semibold text-amber-ink hover:bg-amber-400 active:scale-95 transition-all"
                >
                  <PlusIcon className="h-3 w-3" />
                  Add
                </button>
              ) : (
                // The `onClick={e => e.stopPropagation()}` shield that used to
                // sit here existed ONLY to keep a +/- tap from bubbling to the
                // <article>'s own onClick. That handler is gone (see the note
                // above the <article>), so the shield now guards nothing and is
                // itself a `no-static-element-interactions` defect. `relative
                // z-10` stays — it is what keeps these controls hit-testable
                // ABOVE the stretched trigger button, and is load-bearing.
                <div className="relative z-10 inline-flex items-center gap-0 rounded-full bg-amber-500 text-amber-ink">
                  <button
                    onClick={() => updateQuantity(product.id, quantity - 1)}
                    aria-label={
                      quantity === 1
                        ? `Remove ${product.title} from basket`
                        : `Decrease quantity of ${product.title}`
                    }
                    className="flex h-7 w-7 items-center justify-center rounded-full hover:bg-amber-400 active:scale-95 transition-all"
                  >
                    <Minus className="h-3 w-3" />
                  </button>
                  <span className="min-w-[1.25rem] text-center text-xs font-bold">{quantity}</span>
                  <button
                    onClick={() => updateQuantity(product.id, quantity + 1)}
                    aria-label={`Increase quantity of ${product.title}`}
                    className="flex h-7 w-7 items-center justify-center rounded-full hover:bg-amber-400 active:scale-95 transition-all"
                  >
                    <PlusIcon className="h-3 w-3" />
                  </button>
                </div>
              )}
            </div>
          </div>

          {/* Product image with multi-image indicator */}
          <div className="relative w-24 sm:w-28 flex-shrink-0">
            <SafeImage
              src={primaryImage}
              alt={product.title}
              className="absolute inset-0 w-full h-full object-cover"
              fallbackClassName="w-full h-full bg-gradient-to-br from-cream-100 to-cream"
              fallbackIcon={<Store className="h-8 w-8 text-oxblood/25" />}
              loading="lazy"
            />
            {hasMultipleImages && (
              <span className="absolute top-1.5 right-1.5 bg-black/50 backdrop-blur-sm text-white text-xs font-medium rounded-md px-1.5 py-0.5">
                +{images.length - 1}
              </span>
            )}
            {promo && (
              <Badge
                variant="destructive"
                className="absolute top-1.5 left-1.5 text-xs px-1.5 py-0 shadow-md"
              >
                {promo.discountType === "PERCENTAGE"
                  ? `${promo.discountPercent}% off`
                  : `£${((promo.discountAmountPennies ?? 0) / 100).toFixed(2)} off`}
              </Badge>
            )}
          </div>
        </div>

        {/* Keyboard-reachable dialog trigger (#446).
            The card was a plain `<article onClick>`: a mouse could open the dish
            detail, a keyboard could not open it AT ALL, and because nothing was
            focused at open time there was no element for a dialog to restore
            focus to on close.

            A stretched invisible button rather than `role="button"` on the
            article: `role="button"` makes its descendants presentational, which
            would have taken the real "Add" control away from assistive tech —
            trading one a11y defect for another. Rendered LAST so it stacks over
            the (positioned) image column; the genuinely interactive controls sit
            at `z-10` above it. It has no box of its own, so layout is unchanged.

            THE STACKING ORDER IS LOAD-BEARING AND SURVIVED THE SERVER-RENDER
            SPLIT (#507). Moving this card out of `page.tsx` into this island
            changed which FILE it lives in and nothing about its DOM: `<article>`
            keeps `relative`, the "Add" button and the quantity stepper keep
            `relative z-10`, and this button is still the LAST child. Break any
            one of the three and the dish modal silently becomes unreachable by
            keyboard again — which is precisely the state #446 measured and
            fixed. e2e/storefront-dish-modal-a11y.spec.ts is the guard. */}
        <button
          type="button"
          aria-haspopup="dialog"
          aria-expanded={modalOpen}
          onClick={(e) => {
            e.stopPropagation()
            setModalOpen(true)
          }}
          className="absolute inset-0 rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-amber-500"
        >
          <span className="sr-only">View details for {product.title}</span>
        </button>
      </article>

      {/* Detail modal */}
      <ProductDetailModal
        product={product}
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        quantity={quantity}
        onAdd={handleAddToCart}
        onIncrement={() => updateQuantity(product.id, quantity + 1)}
        onDecrement={() => updateQuantity(product.id, quantity - 1)}
      />
    </>
  )
}

interface ShopConfig {
  announcements: string[]
  featuredProducts: PublicProduct[]
  activePromotions: { label: string; discountPercent: number | null; category: string | null; validUntil: string }[]
}

/**
 * The interactive half of an individual storefront (issues #507, #446).
 *
 * WHAT CHANGED AND WHAT DID NOT. Every capability this page had is still here —
 * the busy/retrying 429 state with its bounded backoff and manual retry, the
 * "Shop not found" fallback, the sticky category rail with smooth scroll, the
 * featured section, promotions, announcements, reviews, the empty-menu state and
 * the floating cart bar. The single change is WHERE the first load comes from.
 *
 * `initial` non-null means the SERVER already fetched and rendered this shop, so
 * there is no fetch on mount and nothing to wait for: the customer sees the menu
 * in the first paint and hydration just takes over the interactions.
 *
 * `initial` null means the server could not get an authoritative answer (429,
 * 5xx, DNS, timeout). It deliberately does NOT guess — it hands over to the
 * fetch path below, which is the code that was already here and already owns the
 * retry budget and the empty-state policy. That is why a rate-limited storefront
 * still cannot fall through to "Shop not found" (F-RATE / #88).
 */
export function ShopDetailClient({
  slug,
  initial,
}: {
  slug: string
  initial: ShopDetail | null
}) {
  const [shop, setShop] = useState<PublicShop | null>(initial?.shop ?? null)
  const [products, setProducts] = useState<ProductsByCategory>(initial?.products ?? {})
  const [reviews, setReviews] = useState<Review[]>(initial?.reviews ?? [])
  const [reviewCount, setReviewCount] = useState(initial?.reviewCount ?? 0)
  const [avgRating, setAvgRating] = useState(initial?.avgRating ?? 0)
  const [, setShopConfig] = useState<ShopConfig | null>(null)
  const [promotions, setPromotions] = useState<PublicPromotion[]>(initial?.promotions ?? [])
  const [announcements, setAnnouncements] = useState<PublicAnnouncement[]>(initial?.announcements ?? [])
  // Server-rendered content is never "loading": the skeleton would replace real
  // HTML with a placeholder on hydration, which is the layout shift this change
  // exists to remove.
  const [loading, setLoading] = useState(initial === null)
  const [activeCategory, setActiveCategory] = useState<string | null>(
    initial ? (Object.keys(initial.products)[0] ?? null) : null
  )
  // Computed on the server for the first render (so it is in the crawled HTML
  // and cannot mismatch during hydration); recomputed locally only after a
  // client-side refetch, which is the only time the server value can be stale.
  const [open, setOpen] = useState(initial?.isOpen ?? true)
  const categoryRefs = useRef<Record<string, HTMLElement | null>>({})
  // F-RATE (#88): a public 429 on the critical shop/products calls must surface
  // a transient "busy / retrying" state, never the authoritative "Shop not
  // found" empty state.
  const [rateLimited, setRateLimited] = useState(false)
  const [retriesExhausted, setRetriesExhausted] = useState(false)
  const retryAttemptRef = useRef(0)
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const loadRef = useRef<() => void>(() => {})
  // One-shot: the server already answered for THIS slug, so the mount effect
  // must not immediately refetch what is on screen. A ref rather than state so
  // it cannot itself schedule a render.
  const serverSeeded = useRef(initial !== null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      // Only the critical shop + products calls can drive the busy state; the 4
      // optional calls already .catch() to defaults so they never reject.
      const [shopRes, productsRes, reviewsRes, configRes, promotionsRes, announcementsRes] = await Promise.all([
        publicApiClient.get<PublicShop>(`/public/shops/${slug}`),
        publicApiClient.get<ProductsByCategory>(`/public/shops/${slug}/products`),
        publicApiClient.get<{ content: Review[], totalElements: number }>(`/public/shops/${slug}/reviews?size=5`).catch(() => ({ data: { content: [], totalElements: 0 } })),
        publicApiClient.get<ShopConfig>(`/public/shops/${slug}/config`).catch(() => ({ data: null })),
        publicApiClient.get<PublicPromotion[]>(`/public/shops/${slug}/promotions`).catch(() => ({ data: [] as PublicPromotion[] })),
        publicApiClient.get<PublicAnnouncement[]>(`/public/shops/${slug}/announcements`).catch(() => ({ data: [] as PublicAnnouncement[] })),
      ])
      setShop(shopRes.data)
      setOpen(isOpenNow(shopRes.data.openingHours))
      setProducts(productsRes.data)
      setReviews(reviewsRes.data.content)
      setReviewCount(reviewsRes.data.totalElements)
      if (reviewsRes.data.content.length > 0) {
        const avg = reviewsRes.data.content.reduce((sum: number, r: Review) => sum + r.foodRating, 0) / reviewsRes.data.content.length
        setAvgRating(Math.round(avg * 10) / 10)
      }
      if (configRes.data) setShopConfig(configRes.data)
      setPromotions(promotionsRes.data || [])
      setAnnouncements(announcementsRes.data || [])
      const cats = Object.keys(productsRes.data)
      if (cats.length > 0) setActiveCategory(cats[0])
      setRateLimited(false)
      setRetriesExhausted(false)
      retryAttemptRef.current = 0
    } catch (err) {
      if (isRateLimitError(err)) {
        setRateLimited(true)
        const attempt = retryAttemptRef.current
        if (attempt < MAX_RETRY_ATTEMPTS) {
          const delay = getRetryDelayMs(err, attempt)
          retryAttemptRef.current = attempt + 1
          if (retryTimerRef.current) clearTimeout(retryTimerRef.current)
          retryTimerRef.current = setTimeout(() => loadRef.current(), delay)
        } else {
          setRetriesExhausted(true)
        }
      } else {
        setShop(null)
        setRateLimited(false)
        setRetriesExhausted(false)
      }
    } finally {
      setLoading(false)
    }
  }, [slug])

  useEffect(() => {
    loadRef.current = load
  }, [load])

  useEffect(() => {
    if (serverSeeded.current) {
      // Consume the seed. A later slug change (client-side nav between shops
      // keeps this island mounted) must still fetch.
      serverSeeded.current = false
      return
    }
    load()
  }, [load])

  // Clear any pending retry timer on unmount to avoid leaks / act() warnings.
  // Its own effect rather than the loader's cleanup, because the loader effect
  // now has an early return and would otherwise register no cleanup at all on
  // the server-seeded first pass.
  useEffect(() => {
    return () => {
      if (retryTimerRef.current) clearTimeout(retryTimerRef.current)
    }
  }, [])

  const handleManualRetry = useCallback(() => {
    retryAttemptRef.current = 0
    setRetriesExhausted(false)
    load()
  }, [load])

  const categories = Object.keys(products)
  const featuredProducts = Object.values(products)
    .flat()
    .filter((p) => p.featured)

  const promotionsByCategory = useMemo(() => {
    const map = new Map<string, PublicPromotion>()
    for (const p of promotions) {
      if (p.category) map.set(p.category, p)
    }
    return map
  }, [promotions])

  function scrollToCategory(cat: string) {
    setActiveCategory(cat)
    categoryRefs.current[cat]?.scrollIntoView({ behavior: "smooth", block: "start" })
  }

  // F-RATE (#88): busy/retrying takes precedence over both the skeleton and the
  // "Shop not found" empty state so a 429 can never fall through to a definitive
  // "this shop is gone" message. Static copy only — never surface error details.
  if (rateLimited) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-16 text-center">
        <Loader2 className="mx-auto h-10 w-10 text-amber-500 animate-spin" />
        <h1 className="mt-4 text-lg font-semibold text-oxblood">
          High demand right now
        </h1>
        {retriesExhausted ? (
          <>
            <p className="mt-1 text-sm text-slate-600">
              This shop is still busy. Please try again in a moment.
            </p>
            <button
              onClick={handleManualRetry}
              className="mt-4 inline-flex items-center gap-1.5 rounded-full bg-amber-500 px-4 py-2 text-sm font-semibold text-amber-ink hover:bg-amber-400 active:scale-95 transition-all"
            >
              Try again
            </button>
          </>
        ) : (
          <p className="mt-1 text-sm text-slate-600">
            This shop is busy — retrying automatically…
          </p>
        )}
        <div className="mt-4">
          <Link
            href="/shop"
            className="inline-flex items-center gap-1 text-sm font-medium text-amber-700 hover:text-amber-800"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to all shops
          </Link>
        </div>
      </div>
    )
  }

  if (loading) {
    return (
      <div className="animate-pulse">
        <div className="h-48 sm:h-64 bg-cream-100" />
        <div className="mx-auto max-w-4xl px-4 py-6 space-y-4">
          <div className="h-6 bg-cream-100 rounded w-1/3" />
          <div className="h-4 bg-cream rounded w-2/3" />
          <div className="h-10 bg-cream rounded" />
          <div className="space-y-3">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="h-24 bg-cream rounded-xl" />
            ))}
          </div>
        </div>
      </div>
    )
  }

  if (!shop) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-16 text-center">
        <Store className="mx-auto h-12 w-12 text-slate-300" />
        <h1 className="mt-4 text-lg font-semibold text-oxblood">Shop not found</h1>
        <p className="mt-1 text-sm text-slate-600">This shop may no longer be available.</p>
        <Link
          href="/shop"
          className="mt-4 inline-flex items-center gap-1 text-sm font-medium text-amber-700 hover:text-amber-800"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to all shops
        </Link>
      </div>
    )
  }

  return (
    <div>
      {/* Hero banner */}
      <div className="relative h-48 sm:h-64 bg-gradient-to-br from-amber-300 via-amber-500 to-oxblood-600">
        {shop.bannerUrl && (
          // FE-2: the banner is the storefront's LCP candidate (largest
          // above-the-fold image, full-bleed at h-48/h-64). `loading="eager"`
          // already stopped it being lazy-loaded; `fetchPriority="high"`
          // additionally tells the browser's PRELOAD SCANNER to fetch it
          // before other same-priority resources, which lazy alone does not
          // do. Additive and safe — no layout/behaviour change, and it is
          // the only image on this page marked "high".
          <SafeImage
            src={shop.bannerUrl}
            alt={`${shop.name} banner`}
            className="absolute inset-0 w-full h-full object-cover"
            loading="eager"
            fetchPriority="high"
          />
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-black/20 to-transparent" />

        {/* Back button */}
        <div className="absolute top-4 left-4">
          <Link
            href="/shop"
            className="inline-flex items-center gap-1 rounded-full bg-black/30 backdrop-blur-sm px-3 py-1.5 text-sm text-white hover:bg-black/50 transition-colors"
          >
            <ArrowLeft className="h-4 w-4" />
            Back
          </Link>
        </div>

        {/* Shop info overlay */}
        <div className="absolute bottom-0 left-0 right-0 p-4 sm:p-6">
          <div className="mx-auto max-w-4xl flex items-end gap-4">
            <div className="h-16 w-16 sm:h-20 sm:w-20 rounded-2xl bg-white shadow-lg ring-2 ring-white overflow-hidden flex-shrink-0">
              <SafeImage
                src={shop.logoUrl}
                alt={shop.name}
                className="h-full w-full object-cover"
                fallbackIcon={<Store className="h-8 w-8 text-oxblood-600" />}
                loading="eager"
              />
            </div>
            <div className="min-w-0 pb-1">
              <h1 className="text-xl sm:text-2xl font-bold text-white truncate">
                {shop.name}
              </h1>
              <div className="mt-1 flex items-center gap-3 text-sm text-white/80">
                <span className={`inline-flex items-center gap-1 text-xs font-medium ${open ? "text-emerald-300" : "text-slate-300"}`}>
                  <span className={`h-1.5 w-1.5 rounded-full ${open ? "bg-emerald-400 animate-pulse" : "bg-slate-400"}`} />
                  {open ? "Open now" : "Closed"}
                </span>
                {reviewCount > 0 && (
                  <span className="inline-flex items-center gap-1 text-xs text-amber-300">
                    <Star className="h-3 w-3 fill-amber-300" />
                    {avgRating} ({reviewCount})
                  </span>
                )}
                {shop.deliveryInfo && (
                  <span className="text-xs truncate">{shop.deliveryInfo}</span>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Shop details bar */}
      <div className="bg-white border-b border-cream-100">
        <div className="mx-auto max-w-4xl px-4 sm:px-6 py-4">
          <div className="flex flex-wrap gap-x-5 gap-y-2 text-xs text-slate-600">
            {shop.address && (
              <span className="inline-flex items-center gap-1">
                <MapPin className="h-3.5 w-3.5 text-slate-400" />
                {shop.address}
              </span>
            )}
            {shop.phone && (
              <a href={`tel:${shop.phone}`} className="inline-flex items-center gap-1 hover:text-slate-700">
                <Phone className="h-3.5 w-3.5 text-slate-400" />
                {shop.phone}
              </a>
            )}
            {shop.email && (
              <a href={`mailto:${shop.email}`} className="inline-flex items-center gap-1 hover:text-slate-700">
                <Mail className="h-3.5 w-3.5 text-slate-400" />
                {shop.email}
              </a>
            )}
            {shop.minimumOrderPennies != null && shop.minimumOrderPennies > 0 && (
              <span className="font-medium text-slate-600">
                Min order {formatPrice(shop.minimumOrderPennies)}
              </span>
            )}
            {/* A null fee falls through to "Free delivery" — pre-existing
                behaviour on this surface, kept as-is when the wire type went
                nullable (review WR-04 fixed the landing card). */}
            {shop.deliveryFeePennies != null && shop.deliveryFeePennies > 0 ? (
              <span className="text-slate-600">
                Delivery {formatPrice(shop.deliveryFeePennies)}
                {shop.freeDeliveryThresholdPennies && (
                  <span className="text-emerald-700 font-medium ml-1">
                    Free over {formatPrice(shop.freeDeliveryThresholdPennies)}
                  </span>
                )}
              </span>
            ) : (
              <span className="text-emerald-700 font-medium">Free delivery</span>
            )}
          </div>

          {shop.description && (
            <p className="mt-2 text-sm text-slate-600 leading-relaxed">
              {shop.description}
            </p>
          )}

          {/* Opening hours (collapsible on mobile) */}
          {shop.openingHours && Object.keys(shop.openingHours).length > 0 && (
            <details className="mt-3 group">
              <summary className="flex items-center gap-1 text-xs text-slate-600 cursor-pointer hover:text-slate-700">
                <Clock className="h-3.5 w-3.5" />
                <span>Opening hours</span>
                <ChevronRight className="h-3 w-3 transition-transform group-open:rotate-90" />
              </summary>
              <div className="mt-2 grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-1 text-xs">
                {DAY_ORDER.map((day) => (
                  <div key={day} className="flex justify-between gap-2">
                    <span className="text-slate-600">{DAY_LABELS[day]}</span>
                    <span className="font-medium text-slate-700">
                      {shop.openingHours?.[day] || "Closed"}
                    </span>
                  </div>
                ))}
              </div>
            </details>
          )}
        </div>
      </div>

      {/* Announcements & Promotions (dedicated public endpoints) */}
      {(announcements.length > 0 || promotions.length > 0) && (
        <div className="bg-white border-b border-cream-100">
          <div className="mx-auto max-w-4xl px-4 sm:px-6 py-3 space-y-2">
            {announcements.length > 0 && (
              <div className="flex items-start gap-2 rounded-lg bg-blue-50 border border-blue-100 px-3 py-2">
                <span className="text-blue-500 text-sm mt-0.5">&#x1f4e2;</span>
                <div className="text-sm text-blue-800">
                  <p className="font-semibold">{announcements[0].title}</p>
                  {announcements[0].body && (
                    <p className="mt-0.5 text-blue-700">{announcements[0].body}</p>
                  )}
                </div>
              </div>
            )}
            {promotions.map((promo, i) => (
              <div key={i} className="flex items-center justify-between rounded-lg bg-amber-50 border border-amber-200 px-3 py-2">
                <span className="text-sm font-medium text-amber-800">{promo.label}</span>
                {promo.discountType === "PERCENTAGE" && promo.discountPercent !== null && (
                  // A11Y-6 (QA council 20260902-134741, A26): white on amber-500 is 2.15:1;
                  // amber-ink (#3A2400) on it is 6.83 — the pairing the Add button on this
                  // page already uses. Dark text, not a darker amber.
                  <span className="rounded-full bg-amber-500 px-2 py-0.5 text-xs font-bold text-amber-ink">
                    {promo.discountPercent}% off
                  </span>
                )}
                {promo.discountType === "FLAT_AMOUNT" && promo.discountAmountPennies !== null && (
                  <span className="rounded-full bg-amber-500 px-2 py-0.5 text-xs font-bold text-amber-ink">
                    £{(promo.discountAmountPennies / 100).toFixed(2)} off
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Customer reviews */}
      {reviews.length > 0 && (
        <div className="bg-white border-b border-cream-100">
          <div className="mx-auto max-w-4xl px-4 sm:px-6 py-4">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-sm font-semibold text-oxblood">
                Customer reviews ({reviewCount})
              </h2>
              <div className="flex items-center gap-1 text-sm">
                <Star className="h-4 w-4 fill-amber-400 text-amber-400" />
                <span className="font-bold text-slate-900">{avgRating}</span>
              </div>
            </div>
            <div className="space-y-3">
              {reviews.slice(0, 3).map((review) => (
                <div key={review.id} className="rounded-lg bg-cream p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-medium text-slate-700">{review.customerName || "Anonymous"}</span>
                    <div className="flex items-center gap-0.5">
                      {[1, 2, 3, 4, 5].map((s) => (
                        <Star key={s} className={`h-3 w-3 ${s <= review.foodRating ? "fill-amber-400 text-amber-400" : "text-slate-300"}`} />
                      ))}
                    </div>
                  </div>
                  {review.comment && (
                    <p className="mt-1 text-xs text-slate-600 line-clamp-2">{review.comment}</p>
                  )}
                  {review.photoUrls && review.photoUrls.length > 0 && (
                    <div className="mt-2 flex gap-1.5">
                      {review.photoUrls.slice(0, 3).map((url, i) => (
                        <SafeImage key={i} src={url} alt="Review photo" className="h-12 w-12 rounded-md object-cover" />
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Category navigation (sticky) */}
      {categories.length > 1 && (
        <div className="sticky top-14 z-40 bg-white border-b border-cream-100 shadow-sm">
          <div className="mx-auto max-w-4xl px-4 sm:px-6">
            {/* Named, and named DIFFERENTLY from the storefront header nav that
                is also on this page (components/storefront/storefront-nav.tsx).
                `landmark-unique` fires on two same-role landmarks sharing a
                name, so "Navigation" on both would satisfy the letter of the
                rule and leave the ambiguity untouched (31-02 / LGL-02). */}
            <nav
              aria-label="Menu categories"
              className="flex gap-1 overflow-x-auto py-2 scrollbar-hide -mx-4 px-4"
            >
              {categories.map((cat) => (
                <button
                  key={cat}
                  onClick={() => scrollToCategory(cat)}
                  className={`whitespace-nowrap rounded-full px-3 py-1.5 text-xs font-medium transition-colors ${
                    activeCategory === cat
                      ? "bg-oxblood text-white"
                      : "bg-cream text-slate-600 hover:bg-cream-100"
                  }`}
                >
                  {cat}
                </button>
              ))}
            </nav>
          </div>
        </div>
      )}

      {/* Menu */}
      <div className="mx-auto max-w-4xl px-4 sm:px-6 py-6">
        {/* Featured section */}
        {featuredProducts.length > 0 && (
          <section className="mb-8">
            <h2 className="flex items-center gap-1.5 text-base font-bold text-oxblood mb-3">
              <Star className="h-4 w-4 text-amber-500" />
              Popular
            </h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {featuredProducts.map((product) => (
                <ProductCard
                  key={product.id}
                  product={product}
                  inFeaturedRail
                  promo={product.category ? promotionsByCategory.get(product.category) : undefined}
                />
              ))}
            </div>
          </section>
        )}

        {/* Category sections */}
        {categories.map((category) => (
          <section
            key={category}
            ref={(el) => { categoryRefs.current[category] = el }}
            className="mb-8 scroll-mt-28"
          >
            <h2 className="text-base font-bold text-oxblood mb-3">{category}</h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {products[category].map((product) => (
                <ProductCard
                  key={product.id}
                  product={product}
                  promo={promotionsByCategory.get(category)}
                />
              ))}
            </div>
          </section>
        ))}

        {/* Menu empty state — a shop with zero assigned products (per-shop
            scoping from 19-02 can now surface this legitimately). */}
        {categories.length === 0 && (
          <div className="flex min-h-[40vh] flex-col items-center justify-center text-center py-12">
            <UtensilsCrossed className="h-12 w-12 text-slate-300" />
            <h2 className="mt-4 text-lg font-semibold text-oxblood">
              No items yet
            </h2>
            <p className="mt-1 text-sm text-slate-600">
              This kitchen hasn&apos;t added anything to its menu.
            </p>
          </div>
        )}
      </div>

      {/* Floating cart bar. A null wire minimum is coerced to 0 — "no minimum"
          — which is what the > 0 gates below already made of it (review WR-04
          made the nullability visible to the compiler; behaviour unchanged).
          Extracted to its own module (#718 review F-5) so its Jest suite no
          longer has to stub THIS file's unrelated module-level imports. */}
      <FloatingCartBar slug={slug} minimumOrderPennies={shop.minimumOrderPennies ?? 0} />
    </div>
  )
}
