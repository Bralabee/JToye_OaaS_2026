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

export interface Order {
  id: string
  tenantId: string
  shopId: string
  status: OrderStatus
  customerName?: string
  customerEmail?: string
  customerPhone?: string
  customerId?: string
  totalAmountPennies: number
  createdAt: string
  updatedAt: string
}

export interface OrderItem {
  id: string
  productId: string
  quantity: number
  unitPricePennies: number
  totalPricePennies: number
  createdAt: string
}

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

// Allergen constants
export const ALLERGENS = [
  { bit: 0, name: "Gluten", icon: "🌾" },
  { bit: 1, name: "Crustaceans", icon: "🦐" },
  { bit: 2, name: "Eggs", icon: "🥚" },
  { bit: 3, name: "Fish", icon: "🐟" },
  { bit: 4, name: "Peanuts", icon: "🥜" },
  { bit: 5, name: "Soybeans", icon: "🫘" },
  { bit: 6, name: "Milk", icon: "🥛" },
  { bit: 7, name: "Nuts", icon: "🌰" },
  { bit: 8, name: "Celery", icon: "🥬" },
  { bit: 9, name: "Mustard", icon: "🌭" },
  { bit: 10, name: "Sesame", icon: "🫘" },
  { bit: 11, name: "Sulphites", icon: "🍷" },
  { bit: 12, name: "Lupin", icon: "🌸" },
  { bit: 13, name: "Molluscs", icon: "🦪" },
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
