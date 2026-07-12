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

export type GateType =
  | "BUSINESS_VERIFIED"
  | "FOOD_HYGIENE_RATING"
  | "ALLERGEN_DATA_COMPLETE"

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
  gates: GateDto[]
}

export interface CreateOnboardingRequest {
  model: OnboardingModel
  shopId: string
  companyNumber?: string
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
