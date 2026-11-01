"use client"

import { useEffect, useState, useRef, useCallback, useId, useSyncExternalStore } from "react"
import Link from "next/link"
import {
  MapPin, Clock, Phone, Mail, ArrowLeft, Store, Bike,
  Flame, Leaf, Star, Timer, ChevronRight, AlertTriangle, AlertCircle,
  Plus as PlusIcon, Minus, UtensilsCrossed, Loader2
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import {
  isRateLimitError,
  getRetryDelayMs,
  MAX_RETRY_ATTEMPTS,
} from "@/lib/public-fetch-retry"
import { DAY_LABELS, DAY_ORDER } from "@/lib/opening-hours"
import { scheduleRows, type OpeningScheduleMap } from "@/lib/opening-schedule"
import { headerPillLabel, readShopAvailability } from "@/lib/shop-availability"
import { ONLY_CHIP_COPY, onlyOfferedType } from "@/lib/fulfilment-offered"
import { PublicShop, PublicProduct, ProductsByCategory, Review, ShopDetail } from "@/types/storefront"
import { lineLimitHintCopy } from "@/components/storefront/product-detail-modal"
import type { PublicAnnouncement } from "@/types/storefront"
import { productAllergenStatement } from "@/lib/product-allergens"
import { useCart } from "@/components/storefront/cart-provider"
import { FloatingCartBar } from "@/components/storefront/floating-cart-bar"
import { SafeImage } from "@/components/ui/safe-image"
import { Badge } from "@/components/ui/badge"
import { ProductDetailModal } from "@/components/storefront/product-detail-modal"
import { ShopAvailabilityNotice } from "@/components/storefront/shop-availability-notice"

/**
 * The "Opening hours" rows (37-21; deferred-items "From 37-22"). A vendor's saved hours live in
 * `openingSchedule`; saving them leaves the old free text on the row, so a list read from the text
 * would print hours the server no longer enforces. Rules, one source, never both:
 *  - STRUCTURED / MIGRATED_FROM_TEXT with a schedule: the schedule, Monday first;
 *  - NEEDS_REVIEW: the vendor's text, because the days V80 could not read are `[]` in the schedule
 *    yet OPEN on the server (D-25 ruling 2026-10-10), so the schedule would print "Closed" for a day
 *    customers can order on;
 *  - a legacy shop, or no schedule: the text when there is any;
 *  - otherwise no list.
 * Display only: the open/closed claim is the server's `openNow` (lib/shop-availability.ts).
 */
function hoursListRows(shop: PublicShop): { day: string; label: string; text: string }[] | null {
  const schedule = shop.openingSchedule
  if (schedule && !shop.legacyAlwaysOpen && shop.hoursSource !== "NEEDS_REVIEW") {
    return scheduleRows(schedule as OpeningScheduleMap)
  }
  const text = shop.openingHours
  if (text && Object.keys(text).length > 0) {
    return DAY_ORDER.map((day) => ({ day, label: DAY_LABELS[day], text: text[day] || "Closed" }))
  }
  return null
}

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

/**
 * SW-JRD-10 / SW-MRC-06 (swarm epic #964): the fixed "View basket" bar covered the last card's Add
 * button and, on a phone, a focused quantity stepper. The bar publishes its own measured height as
 * `--jt-bottom-chrome` (hooks/use-bottom-chrome-height.ts, R-07), and the property is ABSENT when no
 * bar shows, so both rules below read 0px then and change nothing.
 *  - MENU_BOTTOM_PADDING: the menu ends one bar-height plus the old py-6 above the page bottom, so
 *    the last card can always scroll clear of the bar.
 *  - CONTROL_SCROLL_MARGIN: a card control focused while it sits behind the bar is scrolled clear of
 *    it. Measured in Chromium (2026-11-01): a button 37px from the bottom of a 667px viewport, under
 *    an 80px fixed bar, stayed covered on Tab without scroll-margin and was scrolled 55px clear with it.
 */
const MENU_BOTTOM_PADDING = "pb-[calc(var(--jt-bottom-chrome,0px)+1.5rem)]"
const CONTROL_SCROLL_MARGIN = "scroll-mb-[calc(var(--jt-bottom-chrome,0px)+0.75rem)]"

/**
 * SW-CLR-13 (swarm epic #964): true once React has hydrated this page, false in the server HTML.
 * The menu is server-rendered, so an Add button is on screen before its click handler exists; a tap
 * in that window was silently dropped. The card's Add control is disabled until this is true, so
 * the button says it is not ready instead of swallowing the tap (and Playwright's actionability
 * check now waits for it too). useSyncExternalStore with a server snapshot of `false` is React's
 * own hydration-safe way to say "client or server" without a mount effect.
 */
const noopSubscribe = () => () => {}
function useHydrated(): boolean {
  return useSyncExternalStore(
    noopSubscribe,
    () => true,
    () => false
  )
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
  inFeaturedRail = false,
}: {
  product: PublicProduct
  inFeaturedRail?: boolean
}) {
  const [modalOpen, setModalOpen] = useState(false)
  const hydrated = useHydrated()
  const { addItem, items, updateQuantity, lineLimit } = useCart()
  const dietaryTags = product.dietaryTags?.split(",").filter(Boolean) || []
  // #817 / D-18: the allergen line, in words, in every state. Its id is per RENDER (useId), not per
  // product: a featured dish renders twice (rail + category), and two elements sharing one id would
  // describe the rail's Add button with the list copy's line, or with nothing.
  const allergens = productAllergenStatement(product)
  const allergenLineId = useId()
  const cartItem = items.find((i) => i.productId === product.id)
  const quantity = cartItem?.quantity || 0
  // 37-30 (D-20, UI-SPEC § D1 "Quantity stepper"): the stepper stops at the SERVER's per-line
  // maximum, held on the cart context so the modal and the drawer stop at the same number.
  const lineLimitHintId = useId()
  const atLineLimit = lineLimit != null && quantity >= lineLimit

  // 37-52 (UI-SPEC F3, UXT-083): "Add" and the stepper REPLACE each other, so the button a keyboard
  // customer just pressed leaves the DOM and focus fell to <body> — their place in the menu was gone.
  // The card's own controls say where focus goes next; the effect moves it once the new control has
  // rendered. Only THIS card's controls set it, so the dish modal (which shares the cart handlers)
  // never has focus pulled out from under it, and a featured dish's second copy is left alone.
  const addButtonRef = useRef<HTMLButtonElement>(null)
  const plusButtonRef = useRef<HTMLButtonElement>(null)
  const minusButtonRef = useRef<HTMLButtonElement>(null)
  const pendingFocusRef = useRef<"plus" | "add" | null>(null)
  useEffect(() => {
    const pending = pendingFocusRef.current
    if (pending === "plus" && quantity > 0) {
      pendingFocusRef.current = null
      // A one-per-order line disables "+" at once; the remove button is then the stepper's live control.
      const target = plusButtonRef.current && !plusButtonRef.current.disabled ? plusButtonRef.current : minusButtonRef.current
      target?.focus()
    } else if (pending === "add" && quantity === 0) {
      pendingFocusRef.current = null
      addButtonRef.current?.focus()
    }
  }, [quantity])

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

            {/* Allergens (#817, #787; D-09, D-18). In WORDS, never a count: the card used to show
                an amber triangle and a bare "2" beside the price, which a screen reader announced as
                "Halal Spicy £8.50 2", and said nothing at all when the declaration was empty — so
                the persona's dish, whose ingredients say MILK, looked like a dish with nothing to
                declare. Placed ABOVE the bottom row so it precedes "Add" in DOM and reading order,
                and referenced by the Add button's aria-describedby so a screen-reader user who
                jumps straight to the control still hears it.

                Rendered in EVERY state (declared / none declared / flagged, plus the may-contain
                line beneath), so every card carries
                the line and no state is a card one line shorter than its neighbours. The text
                wraps rather than truncates: no allergen is ever hidden behind an ellipsis. */}
            <div id={allergenLineId} className="mt-2 space-y-0.5 text-xs leading-snug">
              {allergens.containsLine && (
                <p className="flex items-start gap-1 font-medium text-amber-700">
                  <AlertTriangle aria-hidden="true" className="mt-px h-3 w-3 flex-shrink-0" />
                  {allergens.containsLine}
                </p>
              )}
              {allergens.undeclaredLine && (
                <p className="flex items-start gap-1 font-semibold text-amber-800">
                  <AlertCircle aria-hidden="true" className="mt-px h-3 w-3 flex-shrink-0" />
                  {allergens.undeclaredLine}
                </p>
              )}
              {allergens.noneDeclaredLine && (
                <p className="text-slate-600">{allergens.noneDeclaredLine}</p>
              )}
              {/* SW-ADE-06 / SW-PRY-03 (swarm epic #964): the "may contain" line, as the dish modal
                  states it. The card printed no cross-contact line at all, so a customer scanning
                  the menu never saw "May contain: Sesame" unless they opened the dish; and an empty
                  list printed nothing, which read the same as "none". It now always says one of
                  the names or "none listed" (the payload cannot tell "none" from "not recorded"). */}
              <p
                className={
                  allergens.mayContain.length > 0 ? "font-medium text-amber-800" : "text-slate-600"
                }
              >
                {allergens.mayContainLine}
              </p>
            </div>

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
              </div>
              {/* Add to cart / quantity controls */}
              {outOfStock ? (
                <span className="inline-flex items-center rounded-full bg-slate-200 px-3 py-1 text-xs font-semibold text-slate-600 cursor-not-allowed">
                  Unavailable
                </span>
              ) : quantity === 0 ? (
                <button
                  ref={addButtonRef}
                  onClick={(e) => {
                    pendingFocusRef.current = "plus"
                    handleAddToCart(e)
                  }}
                  // A11Y-4 (QA council 20260902-134741; WCAG 2.4.6): nine cards on one page
                  // exposed the identical name "Add", and a name-driven actor added the
                  // wrong dish. Visible text stays "Add"; the name says which dish, the
                  // same way the +/- stepper beside it already does.
                  aria-label={`Add ${product.title} to basket${inFeaturedRail ? " (featured)" : ""}`}
                  // #817: the name stays the dish (A11Y-4); the description is its allergen line.
                  aria-describedby={allergenLineId}
                  // SW-CLR-13: disabled in the server HTML, enabled the moment the handler exists.
                  disabled={!hydrated}
                  className={`relative z-10 inline-flex items-center gap-1 rounded-full bg-amber-500 px-3 py-1 text-xs font-semibold text-amber-ink hover:bg-amber-400 active:scale-95 transition-all disabled:cursor-wait disabled:opacity-60 ${CONTROL_SCROLL_MARGIN}`}
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
                    ref={minusButtonRef}
                    onClick={() => {
                      if (quantity === 1) pendingFocusRef.current = "add"
                      updateQuantity(product.id, quantity - 1)
                    }}
                    aria-label={
                      quantity === 1
                        ? `Remove ${product.title} from basket`
                        : `Decrease quantity of ${product.title}`
                    }
                    className={`flex h-7 w-7 items-center justify-center rounded-full hover:bg-amber-400 active:scale-95 transition-all ${CONTROL_SCROLL_MARGIN}`}
                  >
                    <Minus className="h-3 w-3" />
                  </button>
                  <span className="min-w-[1.25rem] text-center text-xs font-bold">{quantity}</span>
                  <button
                    ref={plusButtonRef}
                    onClick={() => updateQuantity(product.id, quantity + 1)}
                    // 37-52 (UI-SPEC F3): the control focus lands on after "Add", named as what it does.
                    aria-label={`Add one more ${product.title}`}
                    aria-describedby={atLineLimit ? lineLimitHintId : undefined}
                    disabled={atLineLimit}
                    className={`flex h-7 w-7 items-center justify-center rounded-full hover:bg-amber-400 active:scale-95 transition-all disabled:cursor-not-allowed disabled:opacity-50 ${CONTROL_SCROLL_MARGIN}`}
                  >
                    <PlusIcon className="h-3 w-3" />
                  </button>
                </div>
              )}
              {atLineLimit && (
                <span id={lineLimitHintId} className="relative z-10 text-sm text-slate-600">
                  {lineLimitHintCopy(lineLimit)}
                </span>
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
            {/* 37-36 (D-17, UXT-008): no promotion chip. Checkout does not apply a discount, so a
                discount chip here advertised a price the customer would not be charged. */}
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
        lineLimit={lineLimit}
        onAdd={handleAddToCart}
        onIncrement={() => updateQuantity(product.id, quantity + 1)}
        onDecrement={() => updateQuantity(product.id, quantity - 1)}
      />
    </>
  )
}

// 37-36 (D-17): the config's `activePromotions` is deliberately not declared, so nothing on this
// page can read a promotion out of it (the server sends [] since 37-35 in any case).
interface ShopConfig {
  announcements: string[]
  featuredProducts: PublicProduct[]
}

/**
 * The interactive half of an individual storefront (issues #507, #446).
 *
 * WHAT CHANGED AND WHAT DID NOT. Every capability this page had is still here —
 * the busy/retrying 429 state with its bounded backoff and manual retry, the
 * "Shop not found" fallback, the sticky category rail with smooth scroll, the
 * featured section, announcements, reviews, the empty-menu state and
 * the floating cart bar. The single change is WHERE the first load comes from.
 * (Promotions were removed later, in 37-36 under D-17: checkout does not apply a
 * discount, so the storefront must not advertise one.)
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
  // 37-30 (D-20): publish the server's per-line maximum to the cart context so every stepper
  // (card, modal, drawer) stops at it. Optional-called: test harnesses that stub useCart without
  // this setter must not throw (the limit is then simply unset — no client limit).
  const { setLineLimit } = useCart()
  const maxLineQuantity = shop?.maxLineQuantity ?? null
  useEffect(() => {
    setLineLimit?.(maxLineQuantity)
  }, [setLineLimit, maxLineQuantity])
  const [products, setProducts] = useState<ProductsByCategory>(initial?.products ?? {})
  const [reviews, setReviews] = useState<Review[]>(initial?.reviews ?? [])
  const [reviewCount, setReviewCount] = useState(initial?.reviewCount ?? 0)
  const [avgRating, setAvgRating] = useState(initial?.avgRating ?? 0)
  const [, setShopConfig] = useState<ShopConfig | null>(null)
  const [announcements, setAnnouncements] = useState<PublicAnnouncement[]>(initial?.announcements ?? [])
  // Server-rendered content is never "loading": the skeleton would replace real
  // HTML with a placeholder on hydration, which is the layout shift this change
  // exists to remove.
  const [loading, setLoading] = useState(initial === null)
  const [activeCategory, setActiveCategory] = useState<string | null>(
    initial ? (Object.keys(initial.products)[0] ?? null) : null
  )
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
      // Only the critical shop + products calls can drive the busy state; the 3
      // optional calls already .catch() to defaults so they never reject. No promotions
      // call (37-36, D-17): the page renders none, so it does not ask for any.
      const [shopRes, productsRes, reviewsRes, configRes, announcementsRes] = await Promise.all([
        publicApiClient.get<PublicShop>(`/public/shops/${slug}`),
        publicApiClient.get<ProductsByCategory>(`/public/shops/${slug}/products`),
        publicApiClient.get<{ content: Review[], totalElements: number }>(`/public/shops/${slug}/reviews?size=5`).catch(() => ({ data: { content: [], totalElements: 0 } })),
        publicApiClient.get<ShopConfig>(`/public/shops/${slug}/config`).catch(() => ({ data: null })),
        publicApiClient.get<PublicAnnouncement[]>(`/public/shops/${slug}/announcements`).catch(() => ({ data: [] as PublicAnnouncement[] })),
      ])
      setShop(shopRes.data)
      setProducts(productsRes.data)
      setReviews(reviewsRes.data.content)
      setReviewCount(reviewsRes.data.totalElements)
      if (reviewsRes.data.content.length > 0) {
        const avg = reviewsRes.data.content.reduce((sum: number, r: Review) => sum + r.foodRating, 0) / reviewsRes.data.content.length
        setAvgRating(Math.round(avg * 10) / 10)
      }
      if (configRes.data) setShopConfig(configRes.data)
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

  // 37-22 (UI-SPEC K3, D-15, theme 2): the pill and the notice read the SERVER's answer
  // (openNow / pausedUntil / pausedIndefinitely / nextOpenAt), on the server-rendered first
  // paint and after a client refetch alike. Nothing here parses the free-text hours to decide
  // it, and an absent openNow (older backend) renders no pill at all.
  const availability = readShopAvailability(shop)
  const pillLabel = headerPillLabel(availability)
  const isOpen = availability.kind === "open"
  const notTakingOrders = availability.kind === "paused" || availability.kind === "closed"
  // 37-49 (UI-SPEC G1, UXT-023): the one fulfilment type the shop takes, or null for both (or for an
  // older payload without the flags). Read by the header chip and the details bar's delivery line.
  const onlyFulfilment = onlyOfferedType(shop)

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

        {/* Back button. SW-MRC-9 (swarm epic #964): white on bg-black/30 over the gold gradient
            measured about 3.6:1. At bg-black/70 the pill's own fill is enough on ANY hero: over the
            worst case, a pure-white banner, it composites to #4d4d4d, and white on that is 8.4:1
            (WCAG AA for this 14px text is 4.5:1). The hover state only darkens it further. */}
        <div className="absolute top-4 left-4">
          <Link
            href="/shop"
            data-testid="shop-hero-back"
            className="inline-flex items-center gap-1 rounded-full bg-black/70 backdrop-blur-sm px-3 py-1.5 text-sm text-white hover:bg-black/80 transition-colors"
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
              {/* 37-52 (UI-SPEC F6, UXT-080): the shop's name wraps (breaking a long word if it must)
                  instead of ending in an ellipsis at 360px with 130% text. The overlay is anchored
                  to the banner's bottom edge, so extra lines grow upward into the banner. */}
              <h1 className="text-xl sm:text-2xl font-bold text-white break-words">
                {shop.name}
              </h1>
              {/* 37-49: flex-wrap (planner assumption for the UI-SPEC G1 375px overflow row): with the
                  fulfilment chip beside the open state and the rating, the row wraps under them
                  instead of pushing past the viewport. */}
              <div
                data-testid="shop-header-status"
                className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-white/80"
              >
                {pillLabel && (
                  <span
                    data-testid="shop-availability-pill"
                    className={`inline-flex items-center gap-1 text-xs font-medium ${isOpen ? "text-emerald-300" : "text-slate-300"}`}
                  >
                    <span
                      aria-hidden="true"
                      className={`h-1.5 w-1.5 rounded-full ${isOpen ? "bg-emerald-400 animate-pulse" : "bg-slate-400"}`}
                    />
                    {pillLabel}
                  </span>
                )}
                {reviewCount > 0 && (
                  <span className="inline-flex items-center gap-1 text-xs text-amber-300">
                    <Star className="h-3 w-3 fill-amber-300" />
                    {avgRating} ({reviewCount})
                  </span>
                )}
                {onlyFulfilment && (
                  <span
                    data-testid="shop-fulfilment-chip"
                    className="inline-flex items-center gap-1 whitespace-nowrap rounded-full bg-black/30 px-2 py-1 text-sm font-semibold text-white backdrop-blur-sm"
                  >
                    {onlyFulfilment === "COLLECTION" ? (
                      <Store className="h-4 w-4" aria-hidden="true" />
                    ) : (
                      <Bike className="h-4 w-4" aria-hidden="true" />
                    )}
                    {ONLY_CHIP_COPY[onlyFulfilment]}
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
            {/* 37-49: a shop that does not deliver advertises no delivery price at all (it used to
                fall through to "Free delivery" for a delivery nobody can order). */}
            {onlyFulfilment === "COLLECTION" ? null : shop.deliveryFeePennies != null && shop.deliveryFeePennies > 0 ? (
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

          {/* Opening hours (collapsible on mobile). 37-21: the rows are the hours the server
              enforces (openingSchedule) whenever that is the whole truth; see hoursListRows. */}
          {(() => {
            const rows = hoursListRows(shop)
            if (!rows) return null
            return (
              <details className="mt-3 group">
                <summary className="flex items-center gap-1 text-xs text-slate-600 cursor-pointer hover:text-slate-700">
                  <Clock className="h-3.5 w-3.5" />
                  <span>Opening hours</span>
                  <ChevronRight className="h-3 w-3 transition-transform group-open:rotate-90" />
                </summary>
                <div className="mt-2 grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-1 text-xs">
                  {rows.map((row) => (
                    <div key={row.day} className="flex justify-between gap-2">
                      <span className="text-slate-600">{row.label}</span>
                      <span className="font-medium text-slate-700">{row.text}</span>
                    </div>
                  ))}
                </div>
              </details>
            )
          })()}
        </div>
      </div>

      {/* K3: when the shop is not taking orders, say so between the header and the menu — a
          full-width block in the content column, before anything the customer could add. */}
      {notTakingOrders && (
        <div className="mx-auto max-w-4xl px-4 sm:px-6 pt-4">
          <ShopAvailabilityNotice shop={shop} />
        </div>
      )}

      {/* Announcements (dedicated public endpoint). The band renders only when there is an
          announcement, so removing the promotions (37-36, D-17) leaves no empty strip above the
          menu: a shop with neither had no band before, and a shop with only promotions has none now. */}
      {announcements.length > 0 && (
        <div data-testid="shop-announcements" className="bg-white border-b border-cream-100">
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
                    {/* UXT-044 (UI-SPEC § C4): the server publishes the minimised name ("Grace P.", a
                        single word as given) since 37-35; the page renders it as is. No name is a
                        "Customer" — "Anonymous" claimed a choice the reviewer never made. */}
                    <span className="text-xs font-medium text-slate-700">{review.customerName?.trim() || "Customer"}</span>
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

      {/* Menu. The bottom padding is bound to the basket bar's published height (SW-JRD-10). */}
      <div data-testid="shop-menu" className={`mx-auto max-w-4xl px-4 sm:px-6 pt-6 ${MENU_BOTTOM_PADDING}`}>
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
