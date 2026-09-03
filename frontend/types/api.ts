// API Response Types

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  first: boolean
  last: boolean
}

// Shop Types
export interface Shop {
  id: string
  tenantId: string
  name: string
  address: string
  slug: string
  description: string | null
  logoUrl: string | null
  bannerUrl: string | null
  phone: string | null
  email: string | null
  latitude: number | null
  longitude: number | null
  openingHours: Record<string, string> | null
  deliveryInfo: string | null
  minimumOrderPennies: number
  published: boolean
  tags: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateShopRequest {
  name: string
  address?: string
  slug?: string
  description?: string
  logoUrl?: string
  bannerUrl?: string
  phone?: string
  email?: string
  latitude?: number
  longitude?: number
  openingHours?: Record<string, string>
  deliveryInfo?: string
  minimumOrderPennies?: number
  published?: boolean
  tags?: string
}

// Media asset types (Phase 24 IMG-04) — mirror the backend
// uk.jtoye.core.media.MediaAssetStatus + MediaAssetDto contract (24-05).
// The safe async upload pipeline turns every upload into a validated, normalized
// WebP derivative; a product references assets and never owns raw bytes.
export type MediaAssetStatus = "PENDING" | "ACTIVE" | "FAILED"

/**
 * A single processed media asset as the dashboard renders it (per gallery entry
 * AND in the review/rejection queue). Mirrors backend MediaAssetDto exactly:
 *   - `url`/`thumbnailUrl` are populated ONLY for an ACTIVE asset (a PENDING or
 *     FAILED asset has no servable object yet).
 *   - `failureReason` is set ONLY on FAILED.
 *   - `flagged` marks an ACTIVE asset awaiting a content-relevance decision
 *     (Keep / Replace) in the review queue (D-04).
 *
 * Phase 27 (27-01) adds the last two, both DERIVED server-side — there is no
 * column for either, so never try to compute them client-side from the other
 * fields:
 *   - `redrivable` — the raw quarantine bytes are still retained, so
 *     POST /media/{assetId}/reprocess can re-run the pipeline over them. This
 *     is the ONLY thing that decides whether Re-process is offered.
 *   - `delayed` — a PENDING asset older than the reaper grace, i.e. one that has
 *     visibly stalled (D-10). Without it a stalled upload is an indefinite
 *     spinner with nothing to act on.
 */
export interface MediaAsset {
  assetId: string
  status: MediaAssetStatus
  flagged: boolean
  failureReason: string | null
  url: string | null
  thumbnailUrl: string | null
  width: number | null
  height: number | null
  redrivable: boolean
  delayed: boolean
}

/**
 * The 202 Accepted body returned by POST /api/v1/products/{id}/image (Phase
 * 24-03): the upload is quarantined + queued, not processed inline, so the
 * response carries the new asset id + a PENDING status rather than a servable
 * image URL. The uploader surfaces the "Processing…" state off this shape.
 */
export interface MediaUploadAccepted {
  assetId: string
  status: MediaAssetStatus
}

// Product Types
export interface Product {
  id: string
  tenantId: string
  sku: string
  title: string
  ingredientsText: string
  allergenMask: number
  pricePennies?: number
  description: string | null
  imageUrl: string | null
  additionalImageUrls: string[]
  // Phase 24 (IMG-04) asset-first media list — primary-first then gallery.
  // Present on single-product responses (by-id/create/update/add-image),
  // absent on list/search (an N+1-avoiding backend decision, 24-05). The flat
  // imageUrl/additionalImageUrls above are retained for the dual-read window (D-03a).
  media?: MediaAsset[] | null
  category: string | null
  displayOrder: number
  available: boolean
  featured: boolean
  preparationTimeMinutes: number | null
  dietaryTags: string | null
  shopId: string | null
  quantityInStock: number | null
  createdAt: string
  updatedAt: string
}

export interface CreateProductRequest {
  sku: string
  title: string
  ingredientsText: string
  allergenMask: number
  pricePennies: number
  description?: string
  imageUrl?: string
  category?: string
  displayOrder?: number
  available?: boolean
  featured?: boolean
  preparationTimeMinutes?: number
  dietaryTags?: string
  shopId?: string
  quantityInStock?: number | null
}

// Order Types
export type OrderStatus =
  | "DRAFT"
  | "PENDING"
  | "CONFIRMED"
  | "PREPARING"
  | "READY"
  | "COMPLETED"
  | "CANCELLED"
  | "REFUNDED"

// Payment status mirrors backend uk.jtoye.core.payment.PaymentStatus.
export type PaymentStatus =
  | "NONE"
  | "PENDING"
  | "AUTHORIZED"
  | "CAPTURED"
  | "FAILED"
  | "REFUNDED"

// Refund reason — Stripe accepts only these three values on Refund.create.
export type RefundReason =
  | "DUPLICATE"
  | "FRAUDULENT"
  | "REQUESTED_BY_CUSTOMER"

// Refund status — UC-3 LOCKED: lowercase mirrors Stripe wire format. CREATING
// is the pre-Stripe sentinel set by RefundService before the first API call.
export type RefundStatus =
  | "CREATING"
  | "succeeded"
  | "failed"
  | "pending"
  | "requires_action"
  | "canceled"

// Refund DTO — matches backend uk.jtoye.core.payment.dto.RefundDto (Phase 17-03).
export interface Refund {
  id: string
  tenantId: string
  orderId: string
  stripeRefundId: string | null
  idempotencyKey: string
  amountPennies: number
  currency: string
  reason: RefundReason
  reasonNote: string | null
  status: RefundStatus
  failureReason: string | null
  requestedAt: string
  updatedAt: string
}

// POST /orders/{id}/refund body. amountPennies omitted = full remaining refund.
export interface CreateRefundRequest {
  amountPennies?: number
  reason: RefundReason
  note?: string
}

export interface Order {
  id: string
  tenantId: string
  shopId: string
  // Customer-facing ORD-… reference (backend OrderDto.orderNumber). Optional:
  // legacy rows created before order numbers existed carry none (FIX-5).
  orderNumber?: string
  status: OrderStatus
  customerName?: string
  customerEmail?: string
  customerPhone?: string
  customerId?: string
  totalAmountPennies: number
  itemCount: number
  createdAt: string
  updatedAt: string
}

export interface OrderItem {
  id: string
  productId: string
  productName: string
  quantity: number
  unitPricePennies: number
  totalPricePennies: number
  createdAt: string
  // Phase 31-10 (LGL-03 / V63): the WRITE-TIME allergen snapshot for this line — what the
  // product declared when the order was placed, not what it declares today. D-04 needs the
  // per-item set so the KDS badge can say WHICH item carries the allergen.
  //
  // null (or absent) means NOT RECORDED: the line predates V63. An empty `allergenNames` with
  // `allergenMask === 0` means the vendor declared none of the 14. Those are DIFFERENT
  // statements — do not write `allergenMask ?? 0` or `allergenNames ?? []`, which would make a
  // historic order silently claim to be allergen-free.
  allergenMask?: number | null
  allergenNames?: string[] | null
}

// Phase 31-10 (LGL-03 / D-03): one ADVISORY reconciliation line. The product's ingredients text
// emphasises an allergen its declared mask omits.
//
// NEVER render this as if it were a declared allergen. It is a text heuristic over the vendor's
// own `**markup**`, carried BESIDE the declaration and never merged into it — S3 renders it as a
// "Check" line, S4 as a "CHECK:" line inside the banner, both naming the product and the
// allergen, both visually distinct from a declared chip by more than colour.
export interface OrderAllergenFlag {
  productName: string
  allergenBit: number
  allergenName: string
}

// How an order is fulfilled — mirrors backend uk.jtoye.core.order.FulfilmentType
// (V45, Phase 19-01). COLLECTION orders carry no delivery address.
export type FulfilmentType = "DELIVERY" | "COLLECTION"

export interface OrderDetail {
  id: string
  tenantId: string
  shopId: string
  orderNumber?: string
  status: OrderStatus
  customerName?: string
  customerEmail?: string
  customerPhone?: string
  notes?: string
  totalAmountPennies: number
  items: OrderItem[]
  createdAt: string
  updatedAt: string
  // Phase 17-03: backend OrderDetailDto now exposes payment + refunds. Optional
  // for backward compatibility with cached responses from older clients.
  paymentStatus?: PaymentStatus
  paymentReference?: string | null
  paymentMethod?: string | null
  refunds?: Refund[]
  // Phase 19-01 (UIX-04/UIX-06): OrderDetailDto now exposes fulfilment +
  // delivery address. Nullable: pre-V45 orders default to DELIVERY with no
  // persisted address; COLLECTION orders carry no address.
  fulfilmentType?: FulfilmentType
  addressLine1?: string | null
  addressLine2?: string | null
  addressCity?: string | null
  addressPostcode?: string | null
  // Phase 31-10 (LGL-03 / V63): the order's allergen picture, rebuilt from the lines' write-time
  // snapshot. This is the DTO the kitchen board consumes, so S4's banner reads these three.
  //
  // ALL THREE ARE null TOGETHER when the order is NOT RECORDED — it predates V63, or one of its
  // lines does (a partial union would silently UNDER-state the set, which is the direction that
  // injures someone). `allergenMask === 0` with empty `allergenNames` means the vendor declared
  // none of the 14, which is NOT the same statement and must not render the same way: S4 shows no
  // banner for a genuinely allergen-free ticket, and a pre-migration ticket must never be allowed
  // to claim it is one.
  //
  // Deliberately absent from `Order` (the list type): the aggregate is derived from the lines,
  // and the list endpoint does not load them — see the note on the backend OrderDto.
  allergenMask?: number | null
  allergenNames?: string[] | null
  allergenFlags?: OrderAllergenFlag[] | null
}

export interface CreateOrderRequest {
  shopId: string
  customerName?: string
  customerEmail?: string
  customerPhone?: string
  customerId?: string
}

// Customer Types
export interface Customer {
  id: string
  tenantId: string
  name: string
  email: string
  phone?: string
  allergenRestrictions: number
  createdAt: string
  updatedAt: string
}

export interface CreateCustomerRequest {
  name: string
  email: string
  phone?: string
  allergenRestrictions?: number
}

// Financial Transaction Types
export type VatRate = "ZERO" | "REDUCED" | "STANDARD" | "EXEMPT"

export interface FinancialTransaction {
  id: string
  tenantId: string
  amountPennies: number
  vatRate: VatRate
  vatAmountPennies: number
  description?: string
  createdAt: string
}

export interface VatBreakdown {
  vatRate: VatRate
  totalAmountPennies: number
  totalVatPennies: number
  count: number
}

export interface FinancialSummary {
  totalRevenuePennies: number
  totalExpensesPennies: number
  netAmountPennies: number
  totalVatPennies: number
  transactionCount: number
  vatBreakdown: VatBreakdown[]
}

// Promotion Types
export type DiscountType = "PERCENTAGE" | "FLAT_AMOUNT"

export interface Promotion {
  id: string
  shopId: string
  label: string
  discountType: DiscountType
  discountPercent: number | null
  discountAmountPennies: number | null
  category: string | null
  validFrom: string
  validUntil: string
  active: boolean
  createdAt: string
}

export interface CreatePromotionRequest {
  label: string
  discountType: DiscountType
  discountPercent?: number
  discountAmountPennies?: number
  category?: string
  validFrom: string
  validUntil: string
  active?: boolean
  shopId: string
}

// Announcement Types
export interface Announcement {
  id: string
  shopId: string
  title: string
  body: string | null
  validFrom: string | null
  validUntil: string | null
  active: boolean
  createdAt: string
}

export interface CreateAnnouncementRequest {
  title: string
  body?: string
  validFrom?: string
  validUntil?: string
  active?: boolean
  shopId: string
}

// Vendor Onboarding Types (Phase 18) — mirror the merged Java DTOs
// (uk.jtoye.core.onboarding.dto). OffsetDateTime -> ISO string; nullable -> `| null`.
export type OnboardingModel = "MARKETPLACE" | "WHITE_LABEL"

export type OnboardingState =
  | "DRAFT"
  | "VERIFYING"
  | "ACTION_REQUIRED"
  | "PENDING_APPROVAL"
  | "APPROVED"
  | "LIVE"
  | "SUSPENDED"
  | "REJECTED"
  | "WITHDRAWN"

// INT-6 (QA council 20260902-134741): ALL eight backend constants, in Java declaration
// order. This union was a hand-maintained 3-of-8 subset; because the pages' copy maps are
// typed Record<GateType, …>, tsc was satisfied and the five missing types rendered as the
// literal "Check". Parity with GateType.java / GateStatus.java / OnboardingState.java is
// now enforced by frontend/__tests__/onboarding-enum-parity.test.ts.
export type GateType =
  | "BUSINESS_VERIFIED"
  | "FOOD_HYGIENE_RATING"
  | "FOOD_BUSINESS_REGISTRATION"
  | "IDENTITY_KYC"
  | "PAYMENTS_CONNECTED"
  | "AGREEMENT_SIGNED"
  | "ALLERGEN_DATA_COMPLETE"
  | "MENU_MINIMUM"

export type GateStatus =
  | "PENDING"
  | "PASSED"
  | "FAILED"
  | "MANUAL_REVIEW"
  | "WAIVED"

// GateDto deliberately withholds raw evidence/externalRef — only the fields
// below are exposed to the vendor; the UI must never attempt to read others.
export interface GateDto {
  gateType: GateType
  status: GateStatus
  mandatory: boolean
  reason: string | null
  checkedAt: string | null
}

export interface OnboardingDto {
  id: string
  status: OnboardingState
  model: OnboardingModel
  shopId: string
  companyNumber: string | null
  submittedAt: string | null
  approvedAt: string | null
  wentLiveAt: string | null
  // ONBD-05: reason shown to the vendor on REJECTED/SUSPENDED (already stored
  // server-side; surfaced on the vendor DTO in 21-03).
  rejectionReason: string | null
  // ONBD-03: derived server-side (VERIFYING && a gate is MANUAL_REVIEW && no gate
  // is still PENDING) so the UI renders an honest "in review" state and never
  // re-computes gate lifecycle math. Kept in EXACT sync with the Java OnboardingDto
  // record — a missing/extra field breaks `next build` (tsc). — 21-03.
  reviewPending: boolean
  gates: GateDto[]
}

export interface CreateOnboardingRequest {
  model: OnboardingModel
  shopId: string
  companyNumber?: string
}

// ONBD-02: vendor corrects the onboarding company number in place (blank =
// sole trader), then resubmits. Mirrors the backend UpdateOnboardingRequest
// (POST /onboarding/company-number). — 21-01.
export interface UpdateOnboardingRequest {
  companyNumber?: string
}

// ONBD-03 admin surface: an admin resolves a stuck gate. Mirrors the backend
// ResolveGateRequest (POST /onboarding/admin/{id}/gates/{gateType}/resolve);
// reason is required for FAIL (enforced server-side), optional for PASS/WAIVE. — 21-03.
export interface ResolveGateRequest {
  decision: "PASS" | "WAIVE" | "FAIL"
  reason?: string
}

// Admin approve/reject queue (#178 slice 2) — mirrors AdminOnboardingDto.
// Adds the review-relevant fields (shopName, rejectionReason) on top of the
// vendor-facing shape; still no raw gate evidence.
export interface AdminOnboardingDto {
  id: string
  status: OnboardingState
  model: OnboardingModel
  shopId: string
  shopName: string | null
  companyNumber: string | null
  submittedAt: string | null
  approvedAt: string | null
  rejectionReason: string | null
  gates: GateDto[]
}

export interface RejectOnboardingRequest {
  reason: string
}

// WebSocket Event Types
export interface OrderStateChangeEvent {
  orderId: string
  tenantId: string
  orderNumber: string
  previousStatus: OrderStatus
  newStatus: OrderStatus
  timestamp: string
}

// Allergen constants (UK FSA 14). Name-only — the previous decorative emoji icons
// were dropped: several were ambiguous or inaccurate (one bean glyph was reused for
// both Soybeans and Sesame, a hot-dog glyph stood in for Mustard), and the name is
// the authoritative label already rendered alongside them everywhere.
export const ALLERGENS = [
  { bit: 0, name: "Gluten" },
  { bit: 1, name: "Crustaceans" },
  { bit: 2, name: "Eggs" },
  { bit: 3, name: "Fish" },
  { bit: 4, name: "Peanuts" },
  { bit: 5, name: "Soybeans" },
  { bit: 6, name: "Milk" },
  { bit: 7, name: "Nuts" },
  { bit: 8, name: "Celery" },
  { bit: 9, name: "Mustard" },
  { bit: 10, name: "Sesame" },
  { bit: 11, name: "Sulphites" },
  { bit: 12, name: "Lupin" },
  { bit: 13, name: "Molluscs" },
]

export function hasAllergen(mask: number, bit: number): boolean {
  return (mask & (1 << bit)) !== 0
}

export function toggleAllergen(mask: number, bit: number): number {
  return mask ^ (1 << bit)
}

export function getAllergenNames(mask: number): string[] {
  return ALLERGENS.filter(a => hasAllergen(mask, a.bit)).map(a => a.name)
}
