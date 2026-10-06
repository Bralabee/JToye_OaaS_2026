/**
 * Local order history on this device.
 * Stores placed orders in localStorage so customers can track
 * them without needing to type order numbers.
 *
 * Each entry carries the email the order was placed with, so this key is
 * personal data: it is disclosed in the cookie policy and removed by an
 * explicit sign-out through `lib/client-storage-keys.ts` (#840) — never by a
 * session lapse.
 */

/** ONE definition of the key, imported by the storage-key registry. */
export const GUEST_ORDERS_KEY = "jtoye-guest-orders"

export interface LocalOrder {
  orderNumber: string
  email: string
  shopSlug: string
  placedAt: string
}

export function getLocalOrders(): LocalOrder[] {
  if (typeof window === "undefined") return []
  try {
    const raw = localStorage.getItem(GUEST_ORDERS_KEY)
    if (!raw) return []
    return JSON.parse(raw)
  } catch {
    return []
  }
}

export function saveLocalOrder(order: LocalOrder) {
  const existing = getLocalOrders()
  const updated = [order, ...existing.filter(o => o.orderNumber !== order.orderNumber)].slice(0, 20)
  localStorage.setItem(GUEST_ORDERS_KEY, JSON.stringify(updated))
}

/** Remove the whole local order history. */
export function clearLocalOrders(): void {
  if (typeof window === "undefined") return
  try {
    localStorage.removeItem(GUEST_ORDERS_KEY)
  } catch {
    /* private mode / storage disabled — nothing stored to clear */
  }
}
