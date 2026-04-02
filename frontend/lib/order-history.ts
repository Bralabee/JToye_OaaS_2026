/**
 * Local order history for guest customers.
 * Stores placed orders in localStorage so customers can track
 * them without needing to type order numbers.
 */

export interface LocalOrder {
  orderNumber: string
  email: string
  shopSlug: string
  placedAt: string
}

export function getLocalOrders(): LocalOrder[] {
  if (typeof window === "undefined") return []
  try {
    const raw = localStorage.getItem("jtoye-guest-orders")
    if (!raw) return []
    return JSON.parse(raw)
  } catch {
    return []
  }
}

export function saveLocalOrder(order: LocalOrder) {
  const existing = getLocalOrders()
  const updated = [order, ...existing.filter(o => o.orderNumber !== order.orderNumber)].slice(0, 20)
  localStorage.setItem("jtoye-guest-orders", JSON.stringify(updated))
}
