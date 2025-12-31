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
  createdAt: string
  updatedAt: string
}

export interface CreateShopRequest {
  name: string
  address: string
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
  createdAt: string
  updatedAt: string
}

export interface CreateProductRequest {
  sku: string
  title: string
  ingredientsText: string
  allergenMask: number
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
  totalPricePennies: number
  createdAt: string
  updatedAt: string
}

export interface CreateOrderRequest {
  shopId: string
  customerName?: string
  customerEmail?: string
  customerPhone?: string
  customerId?: string
  totalPricePennies: number
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
