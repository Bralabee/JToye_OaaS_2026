import type { TraderEntityType } from "@/types/api"

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
  /**
   * #789 (31.1-24, D-10/D-11/D-20): who the customer is buying from — the
   * tenant's legal entity plus this shop's contact details. Carried ONLY by
   * `GET /public/shops/{slug}` (never by the list endpoints) and ABSENT, not
   * null, when the trader has not provided their details (the server writes it
   * NON_NULL so the Phase 38 wire goldens are unchanged). Absent renders
   * SELLER_DETAILS_MISSING_COPY — never an invented or blank field.
   */
  seller?: PublicSeller
}

/**
 * The public seller object (backend SellerIdentityDto). Only what the law
 * requires the customer to see (CCR 2013 Sch 2(b)-(c); E-Commerce Regs 2002
 * reg 6(1)(c),(d),(g)). Optional fields are ABSENT when the trader has none:
 * a company number only for a COMPANY, a VAT number only when registered, a
 * phone only when the shop has one. Numbers render exactly as stored.
 */
export interface PublicSeller {
  legalName: string
  entityType: TraderEntityType
  companyNumber?: string
  vatNumber?: string
  /** Geographic address: line 1, optional line 2, town/city, postcode. */
  addressLines: string[]
  /** The shop's email — required for a live shop (D-20). */
  email?: string
  phone?: string
}

import type { MediaAsset } from "@/types/api"

export type { MediaAsset, MediaAssetStatus } from "@/types/api"

/**
 * How the server said it read `q` — 33-08's `X-Search-Interpretation`.
 *
 * Re-exported from its parser rather than redeclared, so the server page and the
 * client island share ONE definition. Two structurally-identical copies would
 * drift the moment the grammar gains a third reading, and the compiler would say
 * nothing.
 */
export type { SearchInterpretation } from "@/lib/search-interpretation"

import type { VatRateName } from "@/lib/vat"

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
  /**
   * COR-6 (QA-council 20260902-134741): this product's VAT rate, mirroring the backend
   * `uk.jtoye.core.finance.VatRate`. Optional AND nullable for old-backend tolerance — the field
   * did not exist before COR-6, which is precisely why the checkout hardcoded 20% and showed a
   * zero-rated basket a VAT figure it was never charged. `lib/vat.ts` resolves an absent value
   * to STANDARD (no silent zero-rating), matching VatCalculator.predominantRate.
   */
  vatRate?: VatRateName | null
  category: string | null
  dietaryTags: string | null
  preparationTimeMinutes: number | null
  featured: boolean
  inStock: boolean
  /**
   * 31.1-06 (#787, D-09): allergen NAMES the ingredients text emphasises (CAPITALS or `**…**`)
   * that the declared `allergenMask` omits, in catalogue bit order. An ADVISORY reconciliation
   * result, rendered as its own "Ingredients name: MILK – check with the shop" line and never
   * merged into the declared set. Optional for old-backend tolerance: absent reads as "nothing
   * computed", and the card then states only the declaration.
   */
  undeclaredIngredientAllergens?: string[] | null
  /**
   * 31.1-14 (#861, D-16): "may contain" (cross-contact) allergen NAMES the vendor recorded that are
   * NOT already declared, in catalogue bit order. Its own "May contain: Sesame" line; never merged
   * into the declared set. The server sends [] for none; optional for old-backend tolerance.
   */
  mayContainAllergens?: string[] | null
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

import type { OrderAllergenFlag } from "@/types/api"

/**
 * The guest-checkout response (`GuestOrderConfirmation`, core-java storefront/dto). Also what a
 * same-key replay returns, byte for byte.
 *
 * The four allergen fields are 31.1-09's (D-08) and are optional here for old-backend tolerance —
 * an ABSENT field is read exactly like `null`, "not recorded", and never like `[]`/`0`.
 *   acknowledgedAllergenMask / acknowledgedAllergens — what the customer ticked to confirm they had
 *     read (null: no acknowledgement recorded — a vendor-entered or pre-V69 order).
 *   recordedAllergens — the order-line snapshot union the kitchen works from ([] = declared none).
 *   recordedAllergenFlags — ADVISORY reconciliation lines, never merged into either set.
 */
export interface GuestOrderConfirmation {
  orderNumber: string
  status: string
  subtotalPennies: number
  deliveryFeePennies: number
  vatRate: string
  vatAmountPennies: number
  totalAmountPennies: number
  shopName: string
  itemCount: number
  clientSecret: string | null
  allergenWarnings: string[]
  acknowledgedAllergenMask?: number | null
  acknowledgedAllergens?: string[] | null
  recordedAllergens?: string[] | null
  recordedAllergenFlags?: OrderAllergenFlag[] | null
}

/** The RFC 7807 `type` URI the stale-acknowledgement 409 carries (31.1-03). */
export const ALLERGEN_ACK_STALE_PROBLEM_TYPE = "https://jtoye.uk/errors/allergen-acknowledgement-stale"

/**
 * 409 `allergen-acknowledgement-stale` (31.1-03, GlobalExceptionHandler): the set the customer
 * acknowledged is not the basket's declared set at submit. Carries the CURRENT set so the checkout
 * can re-render the panel and ask for a new tick. No order was created and the key was not held.
 */
export interface AllergenAcknowledgementStaleProblem {
  type: string
  title?: string
  status?: number
  detail?: string
  code: "ALLERGEN_ACKNOWLEDGEMENT_STALE"
  /** AllergenCatalog bits 0..13 — what the server will accept for this basket now. */
  currentAllergenMask: number
  /** The names of currentAllergenMask, in AllergenCatalog bit order. */
  currentAllergens: string[]
  /** What the refused submit carried. */
  acknowledgedAllergenMask: number
  /** One entry per basket line, in basket order. */
  lines: Array<{ productId: string; productName: string; allergenMask: number; allergens: string[] }>
}

/** Which channel placed an order (31.1-09): the customer's storefront, or the shop on their behalf. */
export type OrderPlacedVia = "STOREFRONT" | "VENDOR"

/**
 * The allergen record carried by `PublicOrderStatus` (core-java storefront/dto): the tracking
 * response `GET /public/orders/{orderNumber}?email=` and each entry of the signed-in history
 * (`/api/customer-orders` -> `/public/orders/mine`). Phase 31.1-09 (D-07, D-08) and 31.1-13 (D-15).
 *
 * Every field is optional AND nullable: an ABSENT field (older backend) reads exactly like `null`,
 * "not recorded", never like `[]` / `0` / `false`.
 *   acknowledgedAllergenMask / acknowledgedAllergens — what the customer ticked at checkout.
 *     null: no acknowledgement was recorded (the shop placed the order, or it predates V69).
 *   recordedAllergens / recordedAllergenFlags — the order-line snapshot the kitchen works from, and
 *     its ADVISORY reconciliation lines. TRACKING RESPONSE ONLY: the history list always carries
 *     null here BY DESIGN (it reads order columns, never order lines — no N+1), so a list entry's
 *     null means "not loaded with the list", not "not recorded". Never render it as either.
 *   placedVia — STOREFRONT | VENDOR; null when the order predates the channel being recorded.
 *   allergyNoteProvided / allergyNoteAcknowledgedAt — the customer sent an allergy note, and when
 *     the shop marked it read. The note's TEXT is never on this response (T-31.1-46).
 */
export interface PublicOrderAllergenRecord {
  acknowledgedAllergenMask?: number | null
  acknowledgedAllergens?: string[] | null
  recordedAllergens?: string[] | null
  recordedAllergenFlags?: OrderAllergenFlag[] | null
  placedVia?: OrderPlacedVia | null
  allergyNoteProvided?: boolean | null
  /** ISO-8601 instant. */
  allergyNoteAcknowledgedAt?: string | null
}
