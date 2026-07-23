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
  minimumOrderPennies: number
  deliveryFeePennies: number
  freeDeliveryThresholdPennies: number | null
  tags: string | null
  // Whether checkout takes an online card payment (QA-council FIX-6 / M3).
  // Optional for old-backend tolerance: when absent, checkout renders no
  // "How you'll pay" section (the pre-fix behaviour).
  acceptsCardPayments?: boolean
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
