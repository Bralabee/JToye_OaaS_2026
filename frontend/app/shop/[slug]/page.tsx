"use client"

import { useEffect, useState, useRef, useMemo, use } from "react"
import Link from "next/link"
import { motion } from "framer-motion"
import {
  MapPin, Clock, Phone, Mail, ArrowLeft, Store,
  Flame, Leaf, Star, Timer, AlertTriangle,
  ShoppingBag, Plus as PlusIcon, Minus, Megaphone,
} from "lucide-react"
import publicApiClient from "@/lib/public-api-client"
import { PublicShop, PublicProduct, ProductsByCategory, Review } from "@/types/storefront"
import type { PublicPromotion, PublicAnnouncement } from "@/types/storefront"

interface ShopConfig {
  announcements: string[]
  featuredProducts: PublicProduct[]
  activePromotions: { label: string; discountPercent: number | null; category: string | null; validUntil: string }[]
}
import { ALLERGENS, hasAllergen } from "@/types/api"
import { useCart } from "@/components/storefront/cart-provider"
import { SafeImage } from "@/components/ui/safe-image"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import {
  Table,
  TableBody,
  TableCell,
  TableRow,
} from "@/components/ui/table"
import { ProductDetailModal } from "@/components/storefront/product-detail-modal"
import { BrandPlaceholder } from "@/components/storefront/brand-placeholder"
import {
  fadeIn,
  fadeUp,
  listStagger,
  listItem,
  useReducedMotionSafe,
} from "@/lib/motion"
import { cn } from "@/lib/utils"

function formatPrice(pennies: number): string {
  return `£${(pennies / 100).toFixed(2)}`
}

function isOpenNow(hours: Record<string, string> | null): boolean {
  if (!hours || Object.keys(hours).length === 0) return true
  const days = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]
  const now = new Date(new Date().toLocaleString("en-GB", { timeZone: "Europe/London" }))
  const dayKey = days[now.getDay()]
  const todayHours = hours[dayKey]
  if (!todayHours || todayHours.toLowerCase() === "closed") return false
  const match = todayHours.match(/(\d{2}):(\d{2})\s*-\s*(\d{2}):(\d{2})/)
  if (!match) return false
  const nowMinutes = now.getHours() * 60 + now.getMinutes()
  return nowMinutes >= parseInt(match[1]) * 60 + parseInt(match[2]) &&
    nowMinutes < parseInt(match[3]) * 60 + parseInt(match[4])
}

const DAY_ORDER = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const
const DAY_LABELS: Record<string, string> = {
  mon: "Monday", tue: "Tuesday", wed: "Wednesday", thu: "Thursday",
  fri: "Friday", sat: "Saturday", sun: "Sunday",
}

function DietaryBadge({ tag }: { tag: string }) {
  const t = tag.toLowerCase().trim()
  if (t.includes("vegan")) {
    return (
      <Badge variant="success" size="sm" className="rounded-pill">
        <Leaf className="h-3 w-3" strokeWidth={1.5} />
        Vegan
      </Badge>
    )
  }
  if (t.includes("vegetarian")) {
    return (
      <Badge variant="success" size="sm" className="rounded-pill">
        <Leaf className="h-3 w-3" strokeWidth={1.5} />
        Vegetarian
      </Badge>
    )
  }
  if (t.includes("spicy") || t.includes("hot")) {
    return (
      <Badge variant="warning" size="sm" className="rounded-pill">
        <Flame className="h-3 w-3" strokeWidth={1.5} />
        Spicy
      </Badge>
    )
  }
  if (t.includes("gluten")) {
    return (
      <Badge variant="editorial" size="sm" className="rounded-pill">
        GF
      </Badge>
    )
  }
  if (t.includes("halal")) {
    return (
      <Badge variant="info" size="sm" className="rounded-pill">
        Halal
      </Badge>
    )
  }
  return (
    <Badge variant="subtle" size="sm" className="rounded-pill">
      {tag.trim()}
    </Badge>
  )
}

function ProductCard({ product, promo }: { product: PublicProduct; promo?: PublicPromotion }) {
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
    })
  }

  return (
    <>
      <Card
        variant="lifted"
        className={cn(
          "group overflow-hidden cursor-pointer rounded-xl border-border-tone-subtle",
          "focus-within:ring-2 focus-within:ring-border-tone-focus focus-within:ring-offset-2 focus-within:ring-offset-surface-canvas",
        )}
        onClick={() => setModalOpen(true)}
        role="button"
        tabIndex={0}
        aria-label={`View ${product.title}`}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault()
            setModalOpen(true)
          }
        }}
      >
        {/* Product image — 1:1 per spec */}
        <div className="relative aspect-square overflow-hidden bg-surface-muted">
          {primaryImage ? (
            <SafeImage
              src={primaryImage}
              alt={product.title}
              className="absolute inset-0 h-full w-full object-cover transition-transform duration-moderate ease-standard group-hover:scale-[1.02]"
              loading="lazy"
            />
          ) : (
            <BrandPlaceholder
              aspect="aspect-square"
              className="absolute inset-0"
            />
          )}
          {product.featured && (
            <div className="absolute left-3 top-3">
              <Badge variant="editorial" size="sm" className="rounded-pill">
                <Star className="h-3 w-3" strokeWidth={1.5} />
                Popular
              </Badge>
            </div>
          )}
          {promo && (
            <div className="absolute right-3 top-3">
              <Badge variant="danger" size="sm" className="rounded-pill">
                {promo.discountType === "PERCENTAGE"
                  ? `${promo.discountPercent}% off`
                  : `£${((promo.discountAmountPennies ?? 0) / 100).toFixed(2)} off`}
              </Badge>
            </div>
          )}
          {hasMultipleImages && (
            <span className="absolute bottom-3 right-3 rounded-pill bg-surface-card/80 backdrop-blur-sm px-2 py-0.5 text-caption text-ink-secondary">
              +{images.length - 1}
            </span>
          )}
          {outOfStock && (
            <div className="absolute inset-0 flex items-center justify-center bg-surface-canvas/70">
              <Badge variant="danger" size="md">Out of stock</Badge>
            </div>
          )}
        </div>

        <CardContent className="p-5 pt-4 space-y-3">
          <div className="flex items-start justify-between gap-3">
            <h3 className="font-display text-heading-sm font-semibold tracking-tight text-ink-primary line-clamp-1">
              {product.title}
            </h3>
            <span className="font-mono text-body-lg font-semibold text-ink-primary tabular-nums whitespace-nowrap">
              {formatPrice(product.pricePennies)}
            </span>
          </div>

          {product.description && (
            <p className="text-body-sm text-ink-secondary line-clamp-2">
              {product.description}
            </p>
          )}

          {(dietaryTags.length > 0 || allergenList.length > 0 || product.preparationTimeMinutes) && (
            <div className="flex flex-wrap items-center gap-1.5">
              {dietaryTags.map((tag) => (
                <DietaryBadge key={tag} tag={tag} />
              ))}
              {product.preparationTimeMinutes && (
                <Badge variant="subtle" size="sm" className="rounded-pill">
                  <Timer className="h-3 w-3" strokeWidth={1.5} />
                  {product.preparationTimeMinutes} min
                </Badge>
              )}
              {allergenList.length > 0 && (
                <Badge variant="warning" size="sm" className="rounded-pill">
                  <AlertTriangle className="h-3 w-3" strokeWidth={1.5} />
                  {allergenList.length} allergen{allergenList.length === 1 ? "" : "s"}
                </Badge>
              )}
            </div>
          )}

          <div className="pt-2">
            {outOfStock ? (
              <Button variant="subtle" size="sm" disabled className="w-full">
                Unavailable
              </Button>
            ) : quantity === 0 ? (
              <Button
                variant="primary"
                size="sm"
                className="w-full"
                onClick={(e) => handleAddToCart(e)}
              >
                <PlusIcon className="h-3.5 w-3.5" strokeWidth={1.5} />
                Add to cart
              </Button>
            ) : (
              <div
                className="flex items-center justify-between rounded-md bg-brand-primary text-ink-on-brand"
                onClick={(e) => e.stopPropagation()}
              >
                <button
                  type="button"
                  onClick={() => updateQuantity(product.id, quantity - 1)}
                  className="flex h-9 w-9 items-center justify-center rounded-md hover:bg-brand-primary-hover transition-colors duration-fast focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus"
                  aria-label="Decrease quantity"
                >
                  <Minus className="h-3.5 w-3.5" strokeWidth={1.5} />
                </button>
                <span className="font-mono tabular-nums text-sm font-semibold">
                  {quantity} in cart
                </span>
                <button
                  type="button"
                  onClick={() => updateQuantity(product.id, quantity + 1)}
                  className="flex h-9 w-9 items-center justify-center rounded-md hover:bg-brand-primary-hover transition-colors duration-fast focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus"
                  aria-label="Increase quantity"
                >
                  <PlusIcon className="h-3.5 w-3.5" strokeWidth={1.5} />
                </button>
              </div>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Detail modal — logic preserved */}
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

function HoursDialog({ hours }: { hours: Record<string, string> }) {
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button variant="ghost" size="sm" className="gap-1.5">
          <Clock className="h-3.5 w-3.5" strokeWidth={1.5} />
          Opening hours
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Opening hours</DialogTitle>
        </DialogHeader>
        <Table>
          <TableBody>
            {DAY_ORDER.map((day) => {
              const value = hours[day]
              const closed = !value || value.toLowerCase() === "closed"
              return (
                <TableRow key={day}>
                  <TableCell className="py-2 text-ink-secondary">
                    {DAY_LABELS[day]}
                  </TableCell>
                  <TableCell
                    numeric
                    className={cn(
                      "py-2",
                      closed ? "text-ink-tertiary" : "text-ink-primary",
                    )}
                  >
                    {closed ? "Closed" : value}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </DialogContent>
    </Dialog>
  )
}

export default function ShopDetailPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = use(params)
  const [shop, setShop] = useState<PublicShop | null>(null)
  const [products, setProducts] = useState<ProductsByCategory>({})
  const [reviews, setReviews] = useState<Review[]>([])
  const [reviewCount, setReviewCount] = useState(0)
  const [avgRating, setAvgRating] = useState(0)
  const [, setShopConfig] = useState<ShopConfig | null>(null)
  const [promotions, setPromotions] = useState<PublicPromotion[]>([])
  const [announcements, setAnnouncements] = useState<PublicAnnouncement[]>([])
  const [loading, setLoading] = useState(true)
  const [activeCategory, setActiveCategory] = useState<string | null>(null)
  const categoryRefs = useRef<Record<string, HTMLElement | null>>({})

  const heroVariants = useReducedMotionSafe(fadeIn)
  const contentVariants = useReducedMotionSafe(fadeUp)
  const gridVariants = useReducedMotionSafe(listStagger)
  const itemVariants = useReducedMotionSafe(listItem)

  useEffect(() => {
    async function load() {
      setLoading(true)
      try {
        const [shopRes, productsRes, reviewsRes, configRes, promotionsRes, announcementsRes] = await Promise.all([
          publicApiClient.get<PublicShop>(`/public/shops/${slug}`),
          publicApiClient.get<ProductsByCategory>(`/public/shops/${slug}/products`),
          publicApiClient.get<{ content: Review[], totalElements: number }>(`/public/shops/${slug}/reviews?size=5`).catch(() => ({ data: { content: [], totalElements: 0 } })),
          publicApiClient.get<ShopConfig>(`/public/shops/${slug}/config`).catch(() => ({ data: null })),
          publicApiClient.get<PublicPromotion[]>(`/public/shops/${slug}/promotions`).catch(() => ({ data: [] as PublicPromotion[] })),
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
        setPromotions(promotionsRes.data || [])
        setAnnouncements(announcementsRes.data || [])
        const cats = Object.keys(productsRes.data)
        if (cats.length > 0) setActiveCategory(cats[0])
      } catch {
        setShop(null)
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [slug])

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

  if (loading) {
    return (
      <div className="bg-surface-canvas animate-pulse">
        <div className="aspect-[21/9] max-h-[480px] bg-surface-muted" />
        <div className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 py-10 space-y-6">
          <div className="h-10 w-1/2 rounded bg-surface-muted" />
          <div className="h-4 w-2/3 rounded bg-surface-muted/70" />
          <div className="h-10 w-full rounded bg-surface-muted/70" />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="h-80 rounded-xl bg-surface-muted/70" />
            ))}
          </div>
        </div>
      </div>
    )
  }

  if (!shop) {
    return (
      <div className="bg-surface-canvas min-h-[60vh]">
        <div className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 py-24 text-center">
          <div className="mx-auto mb-6">
            <BrandPlaceholder
              aspect="h-24 w-24"
              className="mx-auto rounded-pill"
            />
          </div>
          <h1 className="font-display text-heading-xl text-ink-primary">
            Shop not found
          </h1>
          <p className="mt-3 text-body text-ink-tertiary">
            This shop may no longer be available.
          </p>
          <div className="mt-6">
            <Button variant="secondary" size="md" asChild>
              <Link href="/shop">
                <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
                Back to all shops
              </Link>
            </Button>
          </div>
        </div>
      </div>
    )
  }

  const open = isOpenNow(shop.openingHours)
  const hasHours = shop.openingHours && Object.keys(shop.openingHours).length > 0

  return (
    <div className="bg-surface-canvas">
      {/* Hero banner — 21:9 full-bleed */}
      <motion.section
        variants={heroVariants}
        initial="hidden"
        animate="visible"
        className="relative w-full"
      >
        <div className="relative aspect-[21/9] max-h-[480px] w-full overflow-hidden bg-surface-muted">
          {shop.bannerUrl ? (
            <>
              <SafeImage
                src={shop.bannerUrl}
                alt={`${shop.name} banner`}
                className="absolute inset-0 h-full w-full object-cover"
                loading="eager"
              />
              {/* Single legibility gradient — the spec's one allowed exception */}
              <div
                aria-hidden="true"
                className="absolute inset-x-0 bottom-0 h-2/5 bg-gradient-to-b from-transparent to-surface-canvas"
              />
            </>
          ) : (
            <div className="absolute inset-0 flex items-center justify-center">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src="/brand/mark.svg"
                alt=""
                aria-hidden="true"
                className="h-24 w-24 opacity-[0.08]"
              />
            </div>
          )}
        </div>

        {/* Back link floating top-left */}
        <div className="absolute left-4 top-4 sm:left-6 sm:top-6">
          <Button variant="secondary" size="sm" asChild className="backdrop-blur-sm bg-surface-card/80">
            <Link href="/shop">
              <ArrowLeft className="h-4 w-4" strokeWidth={1.5} />
              Back
            </Link>
          </Button>
        </div>
      </motion.section>

      {/* Header card — overlaps banner bottom */}
      <motion.section
        variants={contentVariants}
        initial="hidden"
        animate="visible"
        className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 -mt-12 sm:-mt-16 relative z-10"
      >
        <Card variant="lifted" className="rounded-xl overflow-hidden">
          <CardContent className="p-6 sm:p-8">
            <div className="flex flex-col gap-6 sm:flex-row sm:items-start">
              {/* Logo */}
              <div className="h-20 w-20 sm:h-24 sm:w-24 flex-shrink-0 overflow-hidden rounded-xl border border-border-tone-subtle bg-surface-card">
                <SafeImage
                  src={shop.logoUrl}
                  alt={`${shop.name} logo`}
                  className="h-full w-full object-cover"
                  fallbackIcon={<Store className="h-8 w-8 text-ink-tertiary" strokeWidth={1.5} />}
                  loading="eager"
                />
              </div>

              <div className="min-w-0 flex-1">
                <h1 className="font-display text-display-lg font-medium tracking-tight text-ink-primary line-clamp-2">
                  {shop.name}
                </h1>
                {shop.description && (
                  <p className="mt-3 max-w-prose text-body-lg text-ink-secondary line-clamp-3">
                    {shop.description}
                  </p>
                )}

                {/* Status badges */}
                <div className="mt-4 flex flex-wrap items-center gap-2">
                  <Badge
                    variant={open ? "success" : "subtle"}
                    size="md"
                    className="rounded-pill"
                  >
                    <span
                      aria-hidden="true"
                      className={cn(
                        "h-1.5 w-1.5 rounded-full",
                        open ? "bg-success" : "bg-ink-tertiary",
                      )}
                    />
                    {open ? "Open now" : "Closed"}
                  </Badge>
                  {shop.deliveryFeePennies > 0 ? (
                    <Badge variant="subtle" size="md" className="rounded-pill">
                      Delivery {formatPrice(shop.deliveryFeePennies)}
                    </Badge>
                  ) : (
                    <Badge variant="brand" size="md" className="rounded-pill">
                      Free delivery
                    </Badge>
                  )}
                  {shop.minimumOrderPennies > 0 && (
                    <Badge variant="subtle" size="md" className="rounded-pill">
                      Min {formatPrice(shop.minimumOrderPennies)}
                    </Badge>
                  )}
                  {shop.freeDeliveryThresholdPennies && (
                    <Badge variant="editorial" size="md" className="rounded-pill">
                      Free over {formatPrice(shop.freeDeliveryThresholdPennies)}
                    </Badge>
                  )}
                  {reviewCount > 0 && (
                    <Badge variant="subtle" size="md" className="rounded-pill">
                      <Star className="h-3 w-3 fill-accent-editorial text-accent-editorial" strokeWidth={1.5} />
                      <span className="font-mono tabular-nums">
                        {avgRating}
                      </span>
                      <span className="text-ink-tertiary">({reviewCount})</span>
                    </Badge>
                  )}
                </div>

                {/* Contact row */}
                <div className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-caption text-ink-tertiary">
                  {shop.address && (
                    <span className="inline-flex items-center gap-1.5">
                      <MapPin className="h-3.5 w-3.5" strokeWidth={1.5} />
                      {shop.address}
                    </span>
                  )}
                  {shop.phone && (
                    <a
                      href={`tel:${shop.phone}`}
                      className="inline-flex items-center gap-1.5 hover:text-ink-primary transition-colors duration-fast"
                    >
                      <Phone className="h-3.5 w-3.5" strokeWidth={1.5} />
                      {shop.phone}
                    </a>
                  )}
                  {shop.email && (
                    <a
                      href={`mailto:${shop.email}`}
                      className="inline-flex items-center gap-1.5 hover:text-ink-primary transition-colors duration-fast"
                    >
                      <Mail className="h-3.5 w-3.5" strokeWidth={1.5} />
                      {shop.email}
                    </a>
                  )}
                  {hasHours && shop.openingHours && (
                    <HoursDialog hours={shop.openingHours} />
                  )}
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      </motion.section>

      {/* Announcements & Promotions */}
      {(announcements.length > 0 || promotions.length > 0) && (
        <section className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 mt-6 space-y-3">
          {announcements.length > 0 && (
            <Card variant="flat" className="rounded-lg border-border-tone-subtle bg-info-subtle">
              <CardContent className="flex items-start gap-3 p-4">
                <Megaphone
                  className="h-5 w-5 flex-shrink-0 text-info"
                  strokeWidth={1.5}
                  aria-hidden="true"
                />
                <div className="min-w-0">
                  <p className="font-display text-heading-sm font-semibold text-ink-primary">
                    {announcements[0].title}
                  </p>
                  {announcements[0].body && (
                    <p className="mt-1 text-body-sm text-ink-secondary">
                      {announcements[0].body}
                    </p>
                  )}
                </div>
              </CardContent>
            </Card>
          )}
          {promotions.map((promo, i) => (
            <Card
              key={i}
              variant="flat"
              className="rounded-lg border-border-tone-subtle bg-accent-editorial-subtle"
            >
              <CardContent className="flex items-center justify-between gap-3 p-4">
                <span className="text-body-sm font-medium text-ink-primary">
                  {promo.label}
                </span>
                {promo.discountType === "PERCENTAGE" && promo.discountPercent !== null && (
                  <Badge variant="editorial" size="md" className="rounded-pill">
                    {promo.discountPercent}% off
                  </Badge>
                )}
                {promo.discountType === "FLAT_AMOUNT" && promo.discountAmountPennies !== null && (
                  <Badge variant="editorial" size="md" className="rounded-pill">
                    £{(promo.discountAmountPennies / 100).toFixed(2)} off
                  </Badge>
                )}
              </CardContent>
            </Card>
          ))}
        </section>
      )}

      {/* Reviews */}
      {reviews.length > 0 && (
        <section className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 mt-10">
          <div className="flex items-center justify-between mb-4">
            <h2 className="font-display text-heading-lg font-semibold tracking-tight text-ink-primary">
              Customer reviews
            </h2>
            <div className="inline-flex items-center gap-2 text-body-sm">
              <Star className="h-4 w-4 fill-accent-editorial text-accent-editorial" strokeWidth={1.5} />
              <span className="font-mono tabular-nums font-semibold text-ink-primary">
                {avgRating}
              </span>
              <span className="text-ink-tertiary">({reviewCount})</span>
            </div>
          </div>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {reviews.slice(0, 3).map((review) => (
              <Card key={review.id} variant="flat" className="rounded-lg">
                <CardContent className="p-4 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-caption font-medium text-ink-primary">
                      {review.customerName || "Anonymous"}
                    </span>
                    <div className="flex items-center gap-0.5" aria-label={`${review.foodRating} out of 5 stars`}>
                      {[1, 2, 3, 4, 5].map((s) => (
                        <Star
                          key={s}
                          className={cn(
                            "h-3 w-3",
                            s <= review.foodRating
                              ? "fill-accent-editorial text-accent-editorial"
                              : "text-ink-quaternary",
                          )}
                          strokeWidth={1.5}
                        />
                      ))}
                    </div>
                  </div>
                  {review.comment && (
                    <p className="text-body-sm text-ink-secondary line-clamp-3">
                      {review.comment}
                    </p>
                  )}
                  {review.photoUrls && review.photoUrls.length > 0 && (
                    <div className="flex gap-1.5 pt-1">
                      {review.photoUrls.slice(0, 3).map((url, i) => (
                        <SafeImage
                          key={i}
                          src={url}
                          alt="Review photo"
                          className="h-12 w-12 rounded-md object-cover"
                        />
                      ))}
                    </div>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>
        </section>
      )}

      {/* Sticky category navigation */}
      {categories.length > 1 && (
        <nav
          className="sticky top-16 z-40 mt-10 border-b border-border-tone-subtle bg-surface-canvas/90 backdrop-blur-sm"
          aria-label="Menu categories"
        >
          <div className="mx-auto max-w-content px-4 sm:px-6 lg:px-8">
            <div className="flex gap-2 overflow-x-auto py-3 scrollbar-hide -mx-4 px-4">
              {categories.map((cat) => {
                const active = activeCategory === cat
                return (
                  <button
                    key={cat}
                    type="button"
                    onClick={() => scrollToCategory(cat)}
                    className="focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas rounded-pill"
                    aria-current={active ? "true" : undefined}
                  >
                    <Badge
                      variant={active ? "brand" : "subtle"}
                      size="md"
                      className="cursor-pointer rounded-pill px-3 whitespace-nowrap"
                    >
                      {cat}
                    </Badge>
                  </button>
                )
              })}
            </div>
          </div>
        </nav>
      )}

      {/* Menu */}
      <section className="mx-auto max-w-content px-4 sm:px-6 lg:px-8 py-10 pb-32">
        {/* Featured */}
        {featuredProducts.length > 0 && (
          <div className="mb-10">
            <div className="mb-4 flex items-center gap-2">
              <Star className="h-4 w-4 text-accent-editorial" strokeWidth={1.5} />
              <h2 className="font-display text-heading-lg font-semibold tracking-tight text-ink-primary">
                Popular
              </h2>
            </div>
            <motion.div
              variants={gridVariants}
              initial="hidden"
              animate="visible"
              className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3"
            >
              {featuredProducts.map((product) => (
                <motion.div key={product.id} variants={itemVariants}>
                  <ProductCard
                    product={product}
                    promo={product.category ? promotionsByCategory.get(product.category) : undefined}
                  />
                </motion.div>
              ))}
            </motion.div>
          </div>
        )}

        {/* Category sections */}
        {categories.map((category) => (
          <section
            key={category}
            ref={(el) => { categoryRefs.current[category] = el }}
            className="mb-12 scroll-mt-32"
          >
            <h2 className="mb-4 font-display text-heading-lg font-semibold tracking-tight text-ink-primary">
              {category}
            </h2>
            <motion.div
              variants={gridVariants}
              initial="hidden"
              animate="visible"
              className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3"
            >
              {products[category].map((product) => (
                <motion.div key={product.id} variants={itemVariants}>
                  <ProductCard
                    product={product}
                    promo={promotionsByCategory.get(category)}
                  />
                </motion.div>
              ))}
            </motion.div>
          </section>
        ))}

        {/* Empty state */}
        {categories.length === 0 && (
          <div className="mx-auto flex max-w-prose flex-col items-center gap-4 py-24 text-center">
            <BrandPlaceholder aspect="h-24 w-24" className="rounded-pill" />
            <div>
              <h2 className="font-display text-heading-lg text-ink-primary">
                Menu coming soon
              </h2>
              <p className="mt-2 text-body text-ink-tertiary">
                This shop hasn&apos;t added any products yet.
              </p>
            </div>
          </div>
        )}
      </section>

      {/* Floating cart bar */}
      <FloatingCartBar slug={slug} minimumOrderPennies={shop.minimumOrderPennies} />
    </div>
  )
}

function FloatingCartBar({ slug, minimumOrderPennies }: { slug: string; minimumOrderPennies: number }) {
  const { itemCount, totalPennies } = useCart()

  if (itemCount === 0) return null

  const belowMinimum = minimumOrderPennies > 0 && totalPennies < minimumOrderPennies

  return (
    <div className="fixed inset-x-0 bottom-0 z-50 px-4 pb-4 sm:px-6 sm:pb-6">
      <div className="mx-auto max-w-content">
        <Link
          href={`/shop/${slug}/cart`}
          className={cn(
            "flex items-center justify-between gap-4 rounded-xl px-5 py-3.5 shadow-float",
            "transition-transform duration-fast active:scale-[0.99]",
            "focus:outline-none focus-visible:ring-2 focus-visible:ring-border-tone-focus focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas",
            belowMinimum
              ? "bg-surface-strong text-ink-primary"
              : "bg-brand-primary text-ink-on-brand",
          )}
        >
          <div className="flex items-center gap-3">
            <div className="relative">
              <ShoppingBag className="h-5 w-5" strokeWidth={1.5} />
              <span className="absolute -right-1.5 -top-1.5 inline-flex h-4 w-4 items-center justify-center rounded-pill bg-surface-card text-[10px] font-bold text-brand-primary">
                {itemCount}
              </span>
            </div>
            <span className="text-body-sm font-medium">View basket</span>
          </div>
          <div className="text-right">
            <span className="font-mono tabular-nums text-body font-semibold">
              {formatPrice(totalPennies)}
            </span>
            {belowMinimum && (
              <p className="text-caption opacity-80">
                Min {formatPrice(minimumOrderPennies)}
              </p>
            )}
          </div>
        </Link>
      </div>
    </div>
  )
}
