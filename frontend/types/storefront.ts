export interface PublicShop {
  slug: string
  name: string
  description: string | null
  address: string | null
  logoUrl: string | null
  bannerUrl: string | null
  phone: string | null
  email: string | null
  latitude: number | null
  longitude: number | null
  openingHours: Record<string, string> | null
  deliveryInfo: string | null
  /**
   * Both nullable ON THE WIRE (review WR-04): they are nullable Longs on
   * PublicShopDto, and CreateShopRequest carries no delivery-fee field at all,
   * so an API-created shop genuinely serialises `deliveryFeePennies: null`.
   * Declaring them `number` here hid that from the compiler and let
   * `null / 100 === 0` render "£0.00 delivery" for a fee nobody has set.
   * null means UNKNOWN and renders nothing; only a wire `0` means free.
   */
  minimumOrderPennies: number | null
  deliveryFeePennies: number | null
  freeDeliveryThresholdPennies: number | null
  tags: string | null
  // Whether checkout takes an online card payment (QA-council FIX-6 / M3).
  // Optional for old-backend tolerance: when absent, checkout renders no
  // "How you'll pay" section (the pre-fix behaviour).
  acceptsCardPayments?: boolean
  /**
   * Kilometres from the coordinate the caller supplied — 33-06's
   * `GET /public/shops?lat=&lon=&radiusKm=`. NULL on every unlocated response,
   * and absent entirely from an older backend, hence optional-AND-nullable:
   * the same old-backend tolerance the `acceptsCardPayments` line above states.
   *
   * It is the number the ORDERING used, computed in SQL. Never recompute it in
   * the browser: a second haversine is a second answer, and the card would then
   * be able to disagree with the position it was given in the list.
   */
  distanceKm?: number | null
}

import type { MediaAsset } from "@/types/api"

export type { MediaAsset, MediaAssetStatus } from "@/types/api"

export interface PublicProduct {
  id: string
  title: string
  description: string | null
  imageUrl: string | null
  imageUrls: string[]
  // Phase 24 (IMG-04) asset-first media list. Optional for old-backend
  // tolerance + the dual-read window (D-03a): when absent the storefront falls
  // back to the flat imageUrl/imageUrls above (asset-first, image_url fallback).
  media?: MediaAsset[] | null
  ingredientsText: string
  allergenMask: number
  pricePennies: number
  category: string | null
  dietaryTags: string | null
  preparationTimeMinutes: number | null
  featured: boolean
  inStock: boolean
}

export type ProductsByCategory = Record<string, PublicProduct[]>

export interface Review {
  id: string
  customerName: string | null
  foodRating: number
  deliveryRating: number | null
  comment: string | null
  photoUrls: string[] | null
  createdAt: string
}

export interface PublicPromotion {
  label: string
  discountType: "PERCENTAGE" | "FLAT_AMOUNT"
  discountPercent: number | null
  discountAmountPennies: number | null
  category: string | null
  validUntil: string
}

export interface PublicAnnouncement {
  title: string
  body: string | null
  validUntil: string | null
}

/**
 * Everything `/shop/[slug]` renders, in one payload (issues #507, #447).
 *
 * Declared HERE rather than beside its loader in `lib/storefront-server.ts`
 * because the client island receives it as a prop. A `"use client"` file
 * importing even a type from the server module would put that module on the
 * client boundary, and that module resolves the INTERNAL core host — the exact
 * infrastructure detail its own header says must not reach a browser bundle.
 */
export interface ShopDetail {
  shop: PublicShop
  products: ProductsByCategory
  reviews: Review[]
  reviewCount: number
  avgRating: number
  promotions: PublicPromotion[]
  announcements: PublicAnnouncement[]
  /**
   * Computed on the SERVER and passed down rather than recomputed during
   * hydration: the open/closed pill is then present in the served HTML (it also
   * feeds the JSON-LD), and the two renders cannot disagree if the clock crosses
   * an opening boundary between them.
   */
  isOpen: boolean
}
